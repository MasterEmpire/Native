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
            outputAudioTranscription: {}
          }
        }))
      }

      ws.onmessage = (event) => {
        try {
          const rawData = event.data
          const response = JSON.parse(rawData)
          
          if (response.setupComplete) {
            console.log("[DIAGNOSTIC] WebSocket: Setup complete. Streaming image frame via realtimeInput.video...")
            
            // For Gemini 3.1 Live, we MUST use camelCase 'realtimeInput' and 'video'
            // with 'image/jpeg' as the mimeType.
            const mediaPayload = {
              realtimeInput: {
                video: {
                  mimeType: "image/jpeg",
                  data: base64Image
                }
              }
            }
            
            const payloadLength = JSON.stringify(mediaPayload).length
            console.log(`[DIAGNOSTIC] WebSocket: Sending image frame (${payloadLength} characters)...`)
            
            if (payloadLength > 1048000) {
              throw new Error(`Payload size of ${payloadLength} bytes exceeds Cloudflare's 1MB WebSocket frame limit. Please compress test.png.`)
            }

            ws.send(JSON.stringify(mediaPayload))

            // Text prompt MUST be sent using realtimeInput.text in Gemini 3.1
            const textPayload = {
              realtimeInput: {
                text: "Please describe what you see in the image in detail."
              }
            }
            console.log("[DIAGNOSTIC] WebSocket: Sending prompt...")
            ws.send(JSON.stringify(textPayload))
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
