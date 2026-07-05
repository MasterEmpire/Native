export default {
  async fetch(request, env) {
    console.log("[CF Worker] Received incoming connection request.");

    // 1. Ensure the request is initiating a WebSocket upgrade
    const upgradeHeader = request.headers.get("Upgrade");
    if (!upgradeHeader || upgradeHeader.toLowerCase() !== "websocket") {
      console.warn("[CF Worker] Rejected connection: Not a WebSocket upgrade request.");
      return new Response("Connect to this worker using WebSockets.", { status: 426 });
    }

    let geminiKey;
    
    // 2. Fetch the API Key from Supabase Pool (Done BEFORE touching WebSocketPair to avoid hangs)
    try {
      const supabaseUrl = env.SUPABASE_URL;
      const serviceRoleKey = env.SUPABASE_SERVICE_ROLE_KEY;

      console.log(`[CF Worker] Querying Supabase RPC at: ${supabaseUrl}`);
      
      if (!supabaseUrl || !serviceRoleKey) {
        throw new Error("Missing SUPABASE_URL or SUPABASE_SERVICE_ROLE_KEY inside Cloudflare environment secrets.");
      }

      const rpcUrl = `${supabaseUrl}/rest/v1/rpc/get_and_rotate_gemini_key`;
      
      const dbResponse = await fetch(rpcUrl, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "apikey": serviceRoleKey,
          "Authorization": `Bearer ${serviceRoleKey}`
        },
        cf: {
          dns: "public"
        }
      });

      console.log(`[CF Worker] Supabase response status: ${dbResponse.status} ${dbResponse.statusText}`);

      if (!dbResponse.ok) {
        const dbErrText = await dbResponse.text();
        throw new Error(`Supabase RPC failed with status ${dbResponse.status}. Raw DB Response: ${dbErrText}`);
      }

      const dbData = await dbResponse.json();
      console.log("[CF Worker] Supabase RPC JSON parsed successfully.", JSON.stringify(dbData));

      if (!Array.isArray(dbData) || dbData.length === 0 || !dbData[0].selected_key) {
        throw new Error("Supabase RPC returned success but no keys were returned. Is your 'api_keys' table empty, or are all keys inactive/cooldown?");
      }

      geminiKey = dbData[0].selected_key;
      console.log("[CF Worker] Successfully selected and rotated an active Gemini API Key.");

    } catch (dbErr) {
      console.error("[CF Worker] CRITICAL DATABASE ERROR:", dbErr.message);
      if (dbErr.stack) console.error(dbErr.stack);

      // Return clean 502 HTTP response (Safe because we haven't accepted WS yet, so no hang!)
      return new Response(JSON.stringify({
        error: "Database key-retrieval failure",
        message: dbErr.message,
        hint: "Check your Supabase URL, Service Role Key, or verify that your get_and_rotate_gemini_key database function is created."
      }), {
        status: 502,
        headers: { "Content-Type": "application/json" }
      });
    }

    // 3. Handshake with Gemini's Multimodal Live API (Done BEFORE touching WebSocketPair)
    let geminiResponse;
    const geminiUrl = `https://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=${geminiKey}`;
    
    try {
      console.log("[CF Worker] Initiating outbound WebSocket handshake to Google Gemini...");
      geminiResponse = await fetch(geminiUrl, {
        headers: { "Upgrade": "websocket" }
      });
      console.log(`[CF Worker] Gemini response status: ${geminiResponse.status} ${geminiResponse.statusText}`);
    } catch (fetchErr) {
      console.error("[CF Worker] CRITICAL GEMINI HANDSHAKE FETCH ERROR:", fetchErr.message);
      return new Response(JSON.stringify({
        error: "Failed to fetch / connect to Google Gemini WebSocket endpoint.",
        message: fetchErr.message
      }), {
        status: 502,
        headers: { "Content-Type": "application/json" }
      });
    }

    const geminiWs = geminiResponse.webSocket;
    if (!geminiWs) {
      let rawDetails = "";
      try {
        rawDetails = await geminiResponse.text();
      } catch (readErr) {
        rawDetails = `Could not read response text: ${readErr.message}`;
      }

      console.error("[CF Worker] GEMINI REJECTED HANDSHAKE:", geminiResponse.status, rawDetails);

      return new Response(JSON.stringify({
        error: "Google Gemini rejected the outbound WebSocket handshake.",
        httpStatus: geminiResponse.status,
        details: rawDetails
      }), {
        status: 500,
        headers: { "Content-Type": "application/json" }
      });
    }

    // 4. Accept connection and instantiate Cloudflare WebSocket Pair ONLY now that everything is successful
    console.log("[CF Worker] Both Supabase and Gemini handshake succeeded. Establishing local client socket...");
    const [client, server] = Object.values(new WebSocketPair());
    server.accept();
    geminiWs.accept();

    // 5. Establish Bidirectional Bridging (Pipe client <-> Gemini)
    
    // Client (Browser) message -> Forwarded to Gemini
    server.addEventListener("message", (event) => {
      try {
        geminiWs.send(event.data);
      } catch (err) {
        console.error("[CF Worker] Error forwarding message from browser to Gemini:", err.message);
        try {
          server.send(JSON.stringify({
            error: "Error forwarding message to Gemini",
            message: err.message
          }));
        } catch (_) {}
      }
    });

    server.addEventListener("close", (event) => {
      console.log(`[CF Worker] Browser closed connection. Code: ${event.code}, Reason: ${event.reason}`);
      try {
        geminiWs.close(event.code, event.reason);
      } catch (_) {}
    });

    server.addEventListener("error", (err) => {
      console.error("[CF Worker] Browser WebSocket error event:", err);
    });

    // Gemini message -> Forwarded back to Client (Browser)
    geminiWs.addEventListener("message", (event) => {
      try {
        let data = event.data;
        if (data instanceof ArrayBuffer) {
          data = new TextDecoder().decode(data);
        } else if (data instanceof Blob) {
          data.text().then(text => {
            server.send(text);
          }).catch(blobErr => {
            server.send(JSON.stringify({
              error: "Failed to read binary Blob message from Gemini",
              message: blobErr.message
            }));
          });
          return;
        }
        server.send(data);
      } catch (err) {
        console.error("[CF Worker] Error forwarding message from Gemini to browser:", err.message);
        try {
          server.send(JSON.stringify({
            error: "Error forwarding Gemini message to browser",
            message: err.message
          }));
        } catch (_) {}
      }
    });

    geminiWs.addEventListener("close", (event) => {
      console.log(`[CF Worker] Gemini closed connection. Code: ${event.code}, Reason: ${event.reason}`);
      try {
        server.close(event.code, event.reason);
      } catch (_) {}
    });

    geminiWs.addEventListener("error", (err) => {
      console.error("[CF Worker] Outbound Gemini WebSocket error event:", err);
      try {
        server.send(JSON.stringify({
          error: "Outbound Gemini WebSocket error occurred",
          message: err.message
        }));
      } catch (_) {}
    });

    // 6. Finalize Handshake cleanly
    console.log("[CF Worker] Handshake finalized. WebSocket pair pipe is active.");
    return new Response(null, {
      status: 101,
      webSocket: client
    });
  }
}
