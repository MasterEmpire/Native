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

  console.log(`[START] Isolated Multimodal Live Session initiated at ${new Date().toISOString()}`)

  try {
    // 1. Initialize Supabase client
    const supabaseUrl = Deno.env.get('SUPABASE_URL') ?? ""
    const supabaseServiceKey = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY') ?? ""
    const supabase = createClient(supabaseUrl, supabaseServiceKey)

    const bucket = "payloads"
    const imagePath = "test.png"

    // 2. Fetch the test image from your Supabase bucket
    console.log(`[STEP 1] Storage: Fetching "${imagePath}" from bucket "${bucket}"...`)
    const { data: fileData, error: storageError } = await supabase
      .storage
      .from(bucket)
      .download(imagePath)

    if (storageError || !fileData) {
      console.error("[ERROR] Storage: Failed to download image:", storageError)
      throw new Error(`Failed to download image: ${storageError?.message}`)
    }

    const imageBytes = await fileData.arrayBuffer()
    console.log(`[STEP 2] Processing: Image size is ${imageBytes.byteLength} bytes. Encoding to Base64...`)
    const base64Image = encodeBase64(imageBytes)
    console.log(`[STEP 3] Processing: Encoding complete. Base64 length: ${base64Image.length} characters.`)

    // 3. Setup WebSocket connection to Cloudflare Worker Proxy
    const workerUrl = "wss://gemini-live-proxy.getyeteklu2.workers.dev"
    console.log(`[STEP 4] WebSocket: Connecting to Cloudflare Proxy: ${workerUrl}`)
    
    const ws = new WebSocket(workerUrl)

    const result = await new Promise((resolve, reject) => {
      let accumulatedText = ""
      let audioFramesReceived = 0
      let sessionComplete = false

      const timeoutId = setTimeout(() => {
        if (!sessionComplete) {
          console.error("[TIMEOUT] WebSocket session timed out after 30 seconds.")
          ws.close()
          reject(new Error("WebSocket transaction timed out."))
        }
      }, 30000)

      ws.onopen = () => {
        console.log("[STEP 5] WebSocket: Connection open. Transmitting Setup frame...")
        
        const setupPayload = {
          setup: {
            model: "models/gemini-3.1-flash-live-preview",
            generationConfig: {
              responseModalities: ["AUDIO"]
            },
            outputAudioTranscription: {} // Enables real-time text transcription alongside voice output
          }
        }
        console.log("[INFO] Sending Setup Payload:", JSON.stringify(setupPayload, null, 2))
        ws.send(JSON.stringify(setupPayload))
      }

      ws.onmessage = (event) => {
        try {
          const rawData = event.data
          // Log EVERY incoming raw message string for analysis
          console.log(`[RAW MESSAGE RECEIVED]:`, rawData)

          const response = JSON.parse(rawData)
          
          // Setup confirmed -> Send the image and text prompt sequentially over realtimeInput
          if (response.setupComplete) {
            console.log("[STEP 6] WebSocket: Setup complete. Transmitting media frame...")
            
            // Part A: Send the image as a real-time media chunk
            const mediaPayload = {
              realtimeInput: {
                mediaChunks: [
                  {
                    mimeType: "image/png",
                    data: base64Image
                  }
                ]
              }
            }
            console.log("[INFO] Sending Media Chunk (Payload size: ", JSON.stringify(mediaPayload).length, " bytes)")
            ws.send(JSON.stringify(mediaPayload))

            // Part B: Immediately follow up with the text trigger prompt in the same stream
            const textPayload = {
              realtimeInput: {
                text: "Please provide a detailed description of what you see in the provided image."
              }
            }
            console.log("[INFO] Sending Text Prompt Payload:", JSON.stringify(textPayload, null, 2))
            ws.send(JSON.stringify(textPayload))
            return
          }

          // Parse incoming streamed content
          if (response.serverContent) {
            const serverContent = response.serverContent
            console.log("[INFO] Parsing serverContent block...")

            // Capture the live text transcription of Gemini's speech turn
            if (serverContent.outputTranscription?.text) {
              const segment = serverContent.outputTranscription.text
              console.log(`[PARSER] Received Transcription segment: "${segment}"`)
              accumulatedText += segment
            }

            // Count returned audio voice frames
            if (serverContent.modelTurn?.parts) {
              for (const part of serverContent.modelTurn.parts) {
                if (part.inlineData?.data) {
                  audioFramesReceived++
                  console.log(`[PARSER] Received Audio Frame #${audioFramesReceived} (${part.inlineData.data.length} characters)`);
                }
              }
            }

            // Session Turn complete -> Complete and resolve
            if (serverContent.turnComplete) {
              console.log("[STEP 7] WebSocket: turnComplete frame received. Closing session successfully.")
              sessionComplete = true
              clearTimeout(timeoutId)
              ws.close()
              resolve({
                success: true,
                prompt: "Please provide a detailed description of what you see in the provided image.",
                transcription: accumulatedText.trim(),
                audio_frames_returned: audioFramesReceived,
                usage: response.usageMetadata || null
              })
            }
          }

        } catch (err) {
          console.error("[ERROR] WebSocket Parsing Exception:", err.message)
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
