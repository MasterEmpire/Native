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

  console.log(`[START] Diagnostic Multimodal Session invoked at ${new Date().toISOString()}`)

  try {
    // 1. Initialize Supabase client
    const supabaseUrl = Deno.env.get('SUPABASE_URL') ?? ""
    const supabaseServiceKey = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY') ?? ""
    const supabase = createClient(supabaseUrl, supabaseServiceKey)

    const bucket = "payloads"
    const imagePath = "test.png"

    // 2. Fetch the test image from your Supabase bucket
    console.log(`[DIAGNOSTIC] Storage: Downloading "${imagePath}" from bucket "${bucket}"...`)
    const { data: fileData, error: storageError } = await supabase
      .storage
      .from(bucket)
      .download(imagePath)

    if (storageError || !fileData) {
      console.error("[ERROR] Storage: Download failed:", storageError)
      throw new Error(`Failed to download image from storage bucket: ${storageError?.message || "File not found"}`)
    }

    const imageBytes = await fileData.arrayBuffer()
    console.log(`[DIAGNOSTIC] Storage: Download successful. Size: ${imageBytes.byteLength} bytes.`)
    
    // Verify payload size against Cloudflare's 1MB WebSocket limit
    if (imageBytes.byteLength > 700000) {
      console.warn("[WARNING] Image size is large. Base64 encoding may exceed Cloudflare's 1MB frame limit.")
    }

    const base64Image = encodeBase64(imageBytes)

    // Generate 1 second of silent 16-bit, 16kHz Mono PCM audio
    // (16000 samples/sec * 2 bytes/sample = 32000 bytes of zeros)
    const silenceBytes = new Uint8Array(32000)
    const base64Silence = encodeBase64(silenceBytes)

    // 3. Setup WebSocket connection to Cloudflare Worker Proxy
    const workerUrl = "wss://gemini-live-proxy.getyeteklu2.workers.dev"
    console.log(`[DIAGNOSTIC] WebSocket: Connecting to proxy: ${workerUrl}`)
    
    const ws = new WebSocket(workerUrl)

    const result = await new Promise((resolve, reject) => {
      let accumulatedText = ""
      let audioFramesReceived = 0
      let sessionComplete = false

      const timeoutId = setTimeout(() => {
        if (!sessionComplete) {
          ws.close()
          reject(new Error("WebSocket transaction timed out after 30 seconds."))
        }
      }, 30000)

      ws.onopen = () => {
        console.log("[DIAGNOSTIC] WebSocket: Connection open. Transmitting Setup frame...")
        
        ws.send(JSON.stringify({
          setup: {
            model: "models/gemini-3.1-flash-live-preview",
            generationConfig: {
              responseModalities: ["AUDIO"]
            },
            realtimeInputConfig: {
              turnCoverage: "TURN_INCLUDES_ALL_INPUT"
            },
            outputAudioTranscription: {}
          }
        }))
      }

      ws.onmessage = async (event) => {
        try {
          const rawData = event.data
          const response = JSON.parse(rawData)
          
          if (response.setupComplete) {
            console.log("[DIAGNOSTIC] WebSocket: Setup complete. Sending image...")
            
            // Part A: Send the image frame
            const mediaPayload = {
              realtimeInput: {
                video: {
                  mimeType: "image/jpeg",
                  data: base64Image
                }
              }
            }
            ws.send(JSON.stringify(mediaPayload))

            // Part B: Wait 2 seconds to allow the visual pipeline on the server to decode and ingest the image context
            console.log("[DIAGNOSTIC] WebSocket: Sleeping for 2 seconds to allow image ingestion...")
            await new Promise((resolve) => setTimeout(resolve, 2000))

            // Part C: Send the prompt text
            const textPayload = {
              realtimeInput: {
                text: "Please describe what you see in the image in detail."
              }
            }
            console.log("[DIAGNOSTIC] WebSocket: Sending prompt text...")
            ws.send(JSON.stringify(textPayload))

            // Part D: Send silent PCM audio to trigger multi-modal fusion VAD
            const audioPayload = {
              realtimeInput: {
                audio: {
                  mimeType: "audio/pcm;rate=16000",
                  data: base64Silence
                }
              }
            }
            console.log("[DIAGNOSTIC] WebSocket: Sending synthetic silence turn trigger...")
            ws.send(JSON.stringify(audioPayload))
            return
          }

          if (response.serverContent) {
            const serverContent = response.serverContent

            if (serverContent.outputTranscription?.text) {
              accumulatedText += serverContent.outputTranscription.text
            }

            if (serverContent.modelTurn?.parts) {
              for (const part of serverContent.modelTurn.parts) {
                if (part.inlineData?.data) {
                  audioFramesReceived++
                }
              }
            }

            if (serverContent.turnComplete) {
              console.log("[DIAGNOSTIC] WebSocket: turnComplete frame received.")
              sessionComplete = true
              clearTimeout(timeoutId)
              ws.close()
              resolve({
                success: true,
                prompt: "Please describe what you see in the image in detail.",
                transcription: accumulatedText.trim(),
                audio_frames_returned: audioFramesReceived,
                usage: response.usageMetadata || null
              })
            }
          }

        } catch (err) {
          clearTimeout(timeoutId)
          ws.close()
          reject(err)
        }
      }

      ws.onerror = (err) => {
        clearTimeout(timeoutId)
        reject(new Error(`WebSocket error occurred: ${JSON.stringify(err)}`))
      }

      ws.onclose = (event) => {
        console.log(`[DIAGNOSTIC] WebSocket: Closed. Code: ${event.code}, Reason: ${event.reason || "None"}`)
        if (!sessionComplete) {
          clearTimeout(timeoutId)
          reject(new Error(`WebSocket closed unexpectedly with code ${event.code}.`))
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
      JSON.stringify({
        success: false,
        error: "Internal server error occurred inside Edge Function.",
        message: error.message,
        stack: error.stack
      }),
      { status: 500, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
    )
  }
})