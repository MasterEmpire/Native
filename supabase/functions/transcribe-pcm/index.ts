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

  console.log(`[START] Edge-to-Worker WebSocket test invoked at ${new Date().toISOString()}`)

  try {
    // 1. Initialize Supabase client
    const supabaseUrl = Deno.env.get('SUPABASE_URL') ?? ""
    const supabaseServiceKey = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY') ?? ""
    const supabase = createClient(supabaseUrl, supabaseServiceKey)

    const bucket = "payloads"
    const audioPath = "test.pcm"
    const imagePath = "test.png"

    // 2. Download test assets in parallel
    console.log(`[INFO] Storage: Fetching assets from bucket "${bucket}"...`)
    const [audioRes, imageRes] = await Promise.all([
      supabase.storage.from(bucket).download(audioPath),
      supabase.storage.from(bucket).download(imagePath)
    ])

    if (audioRes.error || !audioRes.data) {
      console.error("[ERROR] Failed to download audio:", audioRes.error)
      throw new Error(`Failed to download audio file: ${audioRes.error?.message}`)
    }
    if (imageRes.error || !imageRes.data) {
      console.error("[ERROR] Failed to download image:", imageRes.error)
      throw new Error(`Failed to download image file: ${imageRes.error?.message}`)
    }

    // 3. Convert assets to base64
    const [audioBytes, imageBytes] = await Promise.all([
      audioRes.data.arrayBuffer(),
      imageRes.data.arrayBuffer()
    ])

    console.log(`[INFO] Processing: Encoding assets (PCM: ${audioBytes.byteLength} bytes, PNG: ${imageBytes.byteLength} bytes) to Base64...`)
    const base64Audio = encodeBase64(audioBytes)
    const base64Image = encodeBase64(imageBytes)

    // 4. Setup WebSocket Client Connection to Cloudflare Worker Proxy
    const workerUrl = "wss://gemini-live-proxy.getyeteklu2.workers.dev"
    console.log(`[INFO] WebSocket: Opening outbound connection to Cloudflare Proxy: ${workerUrl}`)
    
    const ws = new WebSocket(workerUrl)

    // Return promise wrapper to block Deno execution safely until WS lifecycle completes
    const result = await new Promise((resolve, reject) => {
      let accumulatedText = ""
      let audioFramesReceived = 0
      let sessionComplete = false

      // Enforce timeout safety
      const timeoutId = setTimeout(() => {
        if (!sessionComplete) {
          console.error("[TIMEOUT] WebSocket session timed out after 30 seconds.")
          ws.close()
          reject(new Error("WebSocket transaction timed out."))
        }
      }, 30000)

      ws.onopen = () => {
        console.log("[INFO] WebSocket: Connection open. Transmitting Setup frame...")
        
        // Request Gemini 3.1 Live with AUDIO output and active Transcription overlays
        ws.send(JSON.stringify({
          setup: {
            model: "models/gemini-3.1-flash-live-preview",
            generationConfig: {
              responseModalities: ["AUDIO"]
            },
            outputAudioTranscription: {} // Requests text alongside the live audio
          }
        }))
      }

      ws.onmessage = (event) => {
        try {
          const response = JSON.parse(event.data)
          
          // Setup confirmed -> Stream the multimodal client turn payload
          if (response.setupComplete) {
            console.log("[INFO] WebSocket: Setup complete. Transmitting binary assets inside user turn...")
            
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
                        inlineData: {
                          mimeType: "audio/pcm;rate=16000",
                          data: base64Audio
                        }
                      },
                      {
                        text: "Please do two things: 1. Transcribe the audio exactly. 2. Provide a detailed analysis of the image."
                      }
                    ]
                  }
                ],
                turnComplete: true
              }
            }))
            return
          }

          // Parse streamed content
          if (response.serverContent?.modelTurn?.parts) {
            for (const part of response.serverContent.modelTurn.parts) {
              // Gather the live text transcription
              if (part.text) {
                accumulatedText += part.text
              }
              // Count audio packets returned
              if (part.inlineData?.data) {
                audioFramesReceived++
              }
            }
          }

          // Session Turn complete -> Complete and resolve
          if (response.serverContent?.turnComplete) {
            console.log("[INFO] WebSocket: turnComplete frame received from Gemini.")
            sessionComplete = true
            clearTimeout(timeoutId)
            ws.close()
            resolve({
              success: true,
              prompt: "Please do two things: 1. Transcribe the audio exactly. 2. Provide a detailed analysis of the image.",
              transcription: accumulatedText.trim(),
              audio_frames_returned: audioFramesReceived,
              usage: response.usageMetadata || null
            })
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
