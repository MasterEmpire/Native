export default {
  async fetch(request, env) {
    try {
      // 1. Ensure the request is initiating a WebSocket upgrade
      const upgradeHeader = request.headers.get("Upgrade");
      if (!upgradeHeader || upgradeHeader.toLowerCase() !== "websocket") {
        return new Response("Connect to this worker using WebSockets.", { status: 426 });
      }

      // 2. Instantiate the Cloudflare WebSocket Pair
      const [client, server] = Object.values(new WebSocketPair());
      server.accept();

      // 3. Dynamically retrieve an API key from the Supabase Pool
      let geminiKey;
      try {
        const supabaseUrl = env.SUPABASE_URL;
        const serviceRoleKey = env.SUPABASE_SERVICE_ROLE_KEY;

        if (!supabaseUrl || !serviceRoleKey) {
          throw new Error("Supabase environment variables (SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY) are missing in Cloudflare configuration.");
        }

        const rpcUrl = `${supabaseUrl}/rest/v1/rpc/get_and_rotate_gemini_key`;
        const dbResponse = await fetch(rpcUrl, {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
            "apikey": serviceRoleKey,
            "Authorization": `Bearer ${serviceRoleKey}`
          }
        });

        if (!dbResponse.ok) {
          const dbErrText = await dbResponse.text();
          throw new Error(`Supabase RPC responded with status ${dbResponse.status}: ${dbErrText}`);
        }

        const dbData = await dbResponse.json();
        if (!Array.isArray(dbData) || dbData.length === 0 || !dbData[0].selected_key) {
          throw new Error("No active or available Gemini API keys found in Supabase pool.");
        }

        geminiKey = dbData[0].selected_key;
      } catch (dbErr) {
        const dbErrPayload = JSON.stringify({
          error: "Failed to dynamically retrieve API key from Supabase.",
          message: dbErr.message,
          stack: dbErr.stack
        });
        server.send(dbErrPayload);
        server.close(1011, "Supabase Key Retrieval Failed");
        return new Response(dbErrPayload, {
          status: 502,
          headers: { "Content-Type": "application/json" }
        });
      }

      // 4. Initiate the outbound connection to Gemini's Multimodal Live API
      const geminiUrl = `https://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=${geminiKey}`;
      
      let geminiResponse;
      try {
        geminiResponse = await fetch(geminiUrl, {
          headers: { "Upgrade": "websocket" }
        });
      } catch (fetchErr) {
        const fetchErrPayload = JSON.stringify({
          error: "Failed to fetch / initiate handshake with Google Gemini endpoint.",
          message: fetchErr.message,
          stack: fetchErr.stack
        });
        server.send(fetchErrPayload);
        server.close(1011, "Gemini Fetch Failed");
        return new Response(fetchErrPayload, {
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

        const errPayload = JSON.stringify({
          error: "Could not establish outbound WebSocket connection to Google Gemini.",
          httpStatus: geminiResponse.status,
          httpStatusText: geminiResponse.statusText,
          details: rawDetails
        });

        server.send(errPayload);
        server.close(1011, "Gemini Handshake Rejected");
        return new Response(errPayload, {
          status: 500,
          headers: { "Content-Type": "application/json" }
        });
      }

      geminiWs.accept();

      // 5. Establish Bidirectional Bridging (Pipe client <-> Gemini)
      
      // Client (Browser) message -> Forwarded to Gemini
      server.addEventListener("message", (event) => {
        try {
          geminiWs.send(event.data);
        } catch (err) {
          console.error("Error forwarding message to Gemini:", err);
          try {
            server.send(JSON.stringify({
              error: "Error forwarding message to Gemini",
              message: err.message,
              stack: err.stack
            }));
          } catch (_) {}
        }
      });

      server.addEventListener("close", (event) => {
        try {
          geminiWs.close(event.code, event.reason);
        } catch (_) {}
      });

      server.addEventListener("error", (err) => {
        console.error("Server WebSocket error:", err);
      });

      // Gemini message -> Forwarded back to Client (Browser)
      geminiWs.addEventListener("message", (event) => {
        try {
          let data = event.data;
          
          // Decodes binary ArrayBuffers to clean UTF-8 strings before sending to browser
          if (data instanceof ArrayBuffer) {
            data = new TextDecoder().decode(data);
          } else if (data instanceof Blob) {
            // Fallback if returned as Blob
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
          console.error("Error forwarding message to browser:", err);
          try {
            server.send(JSON.stringify({
              error: "Error forwarding Gemini message to browser",
              message: err.message,
              stack: err.stack
            }));
          } catch (_) {}
        }
      });

      geminiWs.addEventListener("close", (event) => {
        try {
          server.close(event.code, event.reason);
        } catch (_) {}
      });

      geminiWs.addEventListener("error", (err) => {
        console.error("Gemini WebSocket error:", err);
        try {
          server.send(JSON.stringify({
            error: "Outbound Gemini WebSocket error occurred",
            message: err.message
          }));
        } catch (_) {}
      });

      // 6. Finalize Handshake
      return new Response(null, {
        status: 101,
        webSocket: client
      });

    } catch (globalErr) {
      return new Response(JSON.stringify({
        error: "Global Worker Exception Occurred",
        message: globalErr.message,
        stack: globalErr.stack
      }), {
        status: 500,
        headers: { "Content-Type": "application/json" }
      });
    }
  }
}
