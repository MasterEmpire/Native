import { serve } from "https://deno.land/std@0.168.0/http/server.ts"
import { createClient } from "https://esm.sh/@supabase/supabase-js@2"

const corsHeaders = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type',
}

serve(async (req) => {
  if (req.method === 'OPTIONS') {
    return new Response('ok', { headers: corsHeaders })
  }

  console.log(`[START] Grounded Multimodal Live Session invoked at ${new Date().toISOString()}`)

  try {
    // 1. Initialize Supabase client
    const supabaseUrl = Deno.env.get('SUPABASE_URL') ?? ""
    const supabaseServiceKey = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY') ?? ""
    const supabase = createClient(supabaseUrl, supabaseServiceKey)

    const audioUrl = "https://vlzgfaqrnyiqfxxxvtas.supabase.co/storage/v1/object/public/payloads/test.pcm"
    const imageUrl = "https://vlzgfaqrnyiqfxxxvtas.supabase.co/storage/v1/object/public/payloads/test.png"

    // 2. Fetch Gemini API key
    const { data: dbData, error: dbError } = await supabase
      .from('api_keys')
      .select('api_key')
      .eq('service', 'gemini')
      .eq('is_active', true)
      .limit(1)
      .single()

    if (dbError || !dbData?.api_key) {
      console.error("[ERROR] Database pull failed or API key not found:", dbError)
      return new Response(
        JSON.stringify({ error: "Could not retrieve an active Gemini API key.", details: dbError }),
        { status: 500, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
      )
    }

    const geminiApiKey = dbData.api_key

    // 3. Construct URL Grounding Prompt
    const promptText = `I have provided an audio file at ${audioUrl} and an image file at ${imageUrl}. Please do two things:
1. Transcribe the audio exactly.
2. Provide a detailed analysis of the image.`

    // 4. Setup WebSocket Client Connection to Cloudflare Worker Proxy
    const workerUrl = "wss://gemini-live-proxy.getyeteklu2.workers.dev"
    console.log(`[INFO] WebSocket: Connecting to Cloudflare Proxy: ${workerUrl}`)
    
    const ws = new WebSocket(workerUrl)

    const result = await new Promise((resolve, reject) => {
      let accumulatedText = ""
      let audioFramesReceived = 0
      let sessionComplete = false

      // Enforce timeout safety
      const timeoutId = setTimeout(() => {
        if (!sessionComplete) {
          console.error("[TIMEOUT] Session timed out after 30 seconds.")
          ws.close()
          reject(new Error("WebSocket transaction timed out."))
        }
      }, 30000)

      ws.onopen = () => {
        console.log("[INFO] WebSocket: Connection open. Transmitting Setup frame...")
        
        ws.send(JSON.stringify({
          setup: {
            model: "models/gemini-3.1-flash-live-preview",
            generationConfig: {
              responseModalities: ["AUDIO"]
            },
            outputAudioTranscription: {} // Enables real-time text transcription alongside voice output
          }
        }))
      }

      ws.onmessage = (event) => {
        try {
          const response = JSON.parse(event.data)
          
          // Setup confirmed -> Send the lightweight text turn with URL grounding
          if (response.setupComplete) {
            console.log("[INFO] WebSocket: Setup complete. Transmitting low-bandwidth URL grounding turn...")
            
            ws.send(JSON.stringify({
              clientContent: {
                turns: [
                  {
                    role: "user",
                    parts: [
                      {
                        text: promptText
                      }
                    ]
                  }
                ],
                turnComplete: true
              }
            }))
            return
          }

          // Parse incoming streamed content
          if (response.serverContent) {
            const serverContent = response.serverContent

            // Capture the live text transcription of Gemini's speech turn
            if (serverContent.outputTranscription?.text) {
              accumulatedText += serverContent.outputTranscription.text
            }

            // Count returned audio voice segments
            if (serverContent.modelTurn?.parts) {
              for (const part of serverContent.modelTurn.parts) {
                if (part.inlineData?.data) {
                  audioFramesReceived++
                }
              }
            }

            // Session Turn complete -> Complete and resolve
            if (serverContent.turnComplete) {
              console.log("[INFO] WebSocket: turnComplete frame received from Gemini.")
              sessionComplete = true
              clearTimeout(timeoutId)
              ws.close()
              resolve({
                success: true,
                prompt: promptText,
                transcription: accumulatedText.trim(),
                audio_frames_returned: audioFramesReceived,
                usage: response.usageMetadata || null
              })
            }
          }

        } catch (err) {
          console.error("[ERROR] WebSocket: Failed parsing message JSON:", err.message)
        }
      }

      ws.onerror = (err) => {
        console.error("[ERROR] WebSocket: Error occurred:", err)
        clearTimeout(timeoutId)
        reject(new Error("WebSocket failure during connection transit."))
      }

      ws.onclose = (event) => {
        console.log(`[INFO] WebSocket: Connection closed. Code: ${event.code}, Reason: ${event.reason || "None"}`)
        if (!sessionComplete) {
          clearTimeout(timeoutId)
          reject(new Error("WebSocket closed unexpectedly before turnComplete."))
        }
      }
    })

    return new Response(
      JSON.stringify(result),
      { status: 200, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
    )

  } catch (error) { 
    console.error("[CRITICAL] Global exception inside Edge Function:", error)
    return new Response(
      JSON.stringify({ error: "Internal server error occurred.", details: error.message }),
      { status: 500, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
    )
  }
})
