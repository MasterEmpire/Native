import { serve } from "https://deno.land/std@0.168.0/http/server.ts"
import { createClient } from "https://esm.sh/@supabase/supabase-js@2"
import { encodeBase64 } from "https://deno.land/std@0.223.0/encoding/base64.ts"

const corsHeaders = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type',
}

serve(async (req) => {
  if (req.method === 'OPTIONS') {
    return new Response('ok', { headers: corsHeaders })
  }

  console.log(`[START] Image Analysis Live Session invoked at ${new Date().toISOString()}`)

  try {
    // 1. Initialize Supabase client
    const supabaseUrl = Deno.env.get('SUPABASE_URL') ?? ""
    const supabaseServiceKey = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY') ?? ""
    const supabase = createClient(supabaseUrl, supabaseServiceKey)

    const bucket = "payloads"
    const imagePath = "test.png"

    // 2. Fetch the test image from your Supabase bucket
    console.log(`[INFO] Storage: Fetching "${imagePath}" from bucket "${bucket}"...`)
    const { data: fileData, error: storageError } = await supabase
      .storage
      .from(bucket)
      .download(imagePath)

    if (storageError || !fileData) {
      console.error("[ERROR] Storage: Failed to download image:", storageError)
      throw new Error(`Failed to download image: ${storageError?.message}`)
    }

    const imageBytes = await fileData.arrayBuffer()
    console.log(`[INFO] Processing: Encoding image (${imageBytes.byteLength} bytes) to Base64...`)
    const base64Image = encodeBase64(imageBytes)

    // 3. Setup WebSocket connection to Cloudflare Worker Proxy
    const workerUrl = "wss://gemini-live-proxy.getyeteklu2.workers.dev"
    console.log(`[INFO] WebSocket: Connecting to Cloudflare Proxy: ${workerUrl}`)
    
    const ws = new WebSocket(workerUrl)

    const result = await new Promise((resolve, reject) => {
      let accumulatedText = ""
      let audioFramesReceived = 0
      let sessionComplete = false

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
            outputAudioTranscription: {} // Enables text transcription output alongside voice output
          }
        }))
      }

      ws.onmessage = (event) => {
        try {
          const response = JSON.parse(event.data)
          
          // Setup confirmed -> Send user turn containing the image and the prompt
          if (response.setupComplete) {
            console.log("[INFO] WebSocket: Setup complete. Transmitting image turn payload...")
            
            ws.send(JSON.stringify({
              clientContent: {
                turns: [
                  {
                    role: "user",
                    parts: [
                      {
                        inlineData: {
                          mimeType: "image/png",
                          data: base64Image
                        }
                      },
                      {
                        text: "Describe what you see in this image in detail."
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

            // CAPTURE TRANSCRIPTION: Gathers real-time text transcription of Gemini's spoken turn
            if (serverContent.outputTranscription?.text) {
              accumulatedText += serverContent.outputTranscription.text
            }

            // Count returned audio voice frames
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
                prompt: "Describe what you see in this image in detail.",
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
