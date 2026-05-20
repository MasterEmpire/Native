export default {
  async fetch(request, env) {
    try {
      // 1. Ensure the request is initiating a WebSocket upgrade
      const upgradeHeader = request.headers.get("Upgrade");
      if (!upgradeHeader || upgradeHeader.toLowerCase() !== "websocket") {
        return new Response("Connect to this worker using WebSockets.", { status: 426 });
      }

      // 2. Dynamically retrieve an API key from the Supabase Pool FIRST
      // We do this before instantiating WebSockets to avoid hanging the runtime on failures.
      let geminiKey;
      const supabaseUrl = env.SUPABASE_URL;
      const serviceRoleKey = env.SUPABASE_SERVICE_ROLE_KEY;

      if (!supabaseUrl || !serviceRoleKey) {
        return new Response(JSON.stringify({
          error: "Supabase environment variables (SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY) are missing in Cloudflare configuration."
        }), {
          status: 500,
          headers: { "Content-Type": "application/json" }
        });
      }

      const rpcUrl = `${supabaseUrl}/rest/v1/rpc/get_and_rotate_gemini_key`;
      
      let dbResponse;
      try {
        dbResponse = await fetch(rpcUrl, {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
            "apikey": serviceRoleKey,
            "Authorization": `Bearer ${serviceRoleKey}`
          }
        });
      } catch (dbFetchErr) {
        return new Response(JSON.stringify({
          error: "Failed to fetch from Supabase endpoint.",
          message: dbFetchErr.message
        }), {
          status: 502,
          headers: { "Content-Type": "application/json" }
        });
      }

      if (!dbResponse.ok) {
        const dbErrText = await dbResponse.text();
        return new Response(JSON.stringify({
          error: `Supabase RPC responded with status ${dbResponse.status}`,
          details: dbErrText
        }), {
          status: 502,
          headers: { "Content-Type": "application/json" }
        });
      }

      const dbData = await dbResponse.json();
      if (!Array.isArray(dbData) || dbData.length === 0 || !dbData[0].selected_key) {
        return new Response(JSON.stringify({
          error: "No active or available Gemini API keys found in the Supabase database pool."
        }), {
          status: 502,
          headers: { "Content-Type": "application/json" }
        });
      }

      geminiKey = dbData[0].selected_key;

      // 3. Initiate the outbound connection to Gemini's Multimodal Live API
      const geminiUrl = `https://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=${geminiKey}`;
      
      let geminiResponse;
      try {
        geminiResponse = await fetch(geminiUrl, {
          headers: { "Upgrade": "websocket" }
        });
      } catch (fetchErr) {
        return new Response(JSON.stringify({
          error: "Failed to initiate handshake with Google Gemini endpoint.",
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

        return new Response(JSON.stringify({
          error: "Could not establish outbound WebSocket connection to Google Gemini.",
          httpStatus: geminiResponse.status,
          httpStatusText: geminiResponse.statusText,
          details: rawDetails
        }), {
          status: 500,
          headers: { "Content-Type": "application/json" }
        });
      }

      // 4. If both database retrieval and Gemini handshake succeeded,
      // now we can safely instantiate and accept the Client WebSocket pair.
      const [client, server] = Object.values(new WebSocketPair());
      server.accept();
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
              message: err.message
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
          console.error("Error forwarding message to browser:", err);
          try {
            server.send(JSON.stringify({
              error: "Error forwarding Gemini message to browser",
              message: err.message
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

      // 6. Finalize Handshake cleanly
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
