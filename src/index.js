export default {
  async fetch(request, env) {
    // 1. Ensure the request is initiating a WebSocket upgrade
    const upgradeHeader = request.headers.get("Upgrade");
    if (!upgradeHeader || upgradeHeader.toLowerCase() !== "websocket") {
      return new Response("Connect to this worker using WebSockets.", { status: 426 });
    }

    // 2. Instantiate the Cloudflare WebSocket Pair
    const [client, server] = Object.values(new WebSocketPair());
    server.accept();

    // 3. Initiate the outbound connection to Gemini's Multimodal Live API
    const geminiUrl = `wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=${env.GEMINI_API_KEY}`;
    
    const geminiResponse = await fetch(geminiUrl, {
      headers: { "Upgrade": "websocket" }
    });

    const geminiWs = geminiResponse.webSocket;
    if (!geminiWs) {
      server.send(JSON.stringify({ error: "Could not establish outbound WebSocket connection to Google Gemini." }));
      server.close();
      return new Response(
        "Internal Gemini WS Error", 
        { status: 500 }
      );
    }

    geminiWs.accept();

    // 4. Establish Bidirectional Bridging (Pipe client <-> Gemini)
    
    // Client (Browser) message -> Forwarded to Gemini
    server.addEventListener("message", (event) => {
      try {
        geminiWs.send(event.data);
      } catch (err) {
        console.error("Error forwarding message to Gemini:", err);
      }
    });

    server.addEventListener("close", (event) => {
      geminiWs.close(event.code, event.reason);
    });

    // Gemini message -> Forwarded back to Client (Browser)
    geminiWs.addEventListener("message", (event) => {
      try {
        server.send(event.data);
      } catch (err) {
        console.error("Error forwarding message to browser:", err);
      }
    });

    geminiWs.addEventListener("close", (event) => {
      server.close(event.code, event.reason);
    });

    // 5. Finalize Handshake
    return new Response(null, {
      status: 101,
      webSocket: client
    });
  }
}
