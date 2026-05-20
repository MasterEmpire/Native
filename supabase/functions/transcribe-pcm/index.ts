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

  console.log(`[START] Multimodal Function invoked at ${new Date().toISOString()}`)

  try {
    // 1. Initialize Supabase client
    const supabaseUrl = Deno.env.get('SUPABASE_URL') ?? ""
    const supabaseServiceKey = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY') ?? ""
    const supabase = createClient(supabaseUrl, supabaseServiceKey)

    // 2. Parse request payload
    let body = {}
    try {
      body = await req.json()
    } catch (_err) {
      // Fallback if empty
    }

    const {
      bucket,
      audioPath = "test.pcm",
      imagePath = "test.png",
      prompt = "Please provide: 1. A transcription of the audio. 2. A detailed explanation of what you see in the image."
    } = body

    // Define default public URLs
    const audioUrl = body.audioUrl || body.fileUrl || (!bucket ? "https://vlzgfaqrnyiqfxxxvtas.supabase.co/storage/v1/object/public/payloads/test.pcm" : null)
    const imageUrl = body.imageUrl || (!bucket ? "https://vlzgfaqrnyiqfxxxvtas.supabase.co/storage/v1/object/public/payloads/test.png" : null)

    // 3. Fetch Gemini API key
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

    // Helper function to fetch files from URL or Bucket
    const fetchAudio = async () => {
      if (audioUrl) {
        console.log(`[INFO] Audio: Fetching from URL: ${audioUrl}`)
        const res = await fetch(audioUrl)
        if (!res.ok) throw new Error(`Audio fetch failed with status ${res.status}`)
        return {
          buffer: await res.arrayBuffer(),
          mimeType: audioUrl.endsWith('.wav') ? 'audio/wav' : 'audio/pcm'
        }
      } else if (bucket && audioPath) {
        console.log(`[INFO] Audio: Downloading "${audioPath}" from bucket "${bucket}"`)
        const { data, error } = await supabase.storage.from(bucket).download(audioPath)
        if (error || !data) throw new Error(`Audio storage download failed: ${error?.message}`)
        return {
          buffer: await data.arrayBuffer(),
          mimeType: audioPath.endsWith('.wav') ? 'audio/wav' : 'audio/pcm'
        }
      }
      throw new Error("No audio source specified.")
    }

    const fetchImage = async () => {
      if (imageUrl) {
        console.log(`[INFO] Image: Fetching from URL: ${imageUrl}`)
        const res = await fetch(imageUrl)
        if (!res.ok) throw new Error(`Image fetch failed with status ${res.status}`)
        return {
          buffer: await res.arrayBuffer(),
          mimeType: imageUrl.endsWith('.jpg') || imageUrl.endsWith('.jpeg') ? 'image/jpeg' : 'image/png'
        }
      } else if (bucket && imagePath) {
        console.log(`[INFO] Image: Downloading "${imagePath}" from bucket "${bucket}"`)
        const { data, error } = await supabase.storage.from(bucket).download(imagePath)
        if (error || !data) throw new Error(`Image storage download failed: ${error?.message}`)
        return {
          buffer: await data.arrayBuffer(),
          mimeType: imagePath.endsWith('.jpg') || imagePath.endsWith('.jpeg') ? 'image/jpeg' : 'image/png'
        }
      }
      throw new Error("No image source specified.")
    }

    // 4. Download both files concurrently to save time
    console.log("[INFO] Storage: Launching parallel file downloads...")
    let audioFile, imageFile
    try {
      [audioFile, imageFile] = await Promise.all([fetchAudio(), fetchImage()])
    } catch (downloadErr) {
      console.error("[ERROR] Storage: File download failed during parallel execution:", downloadErr)
      return new Response(
        JSON.stringify({ error: "Failed to fetch media assets.", details: downloadErr.message }),
        { status: 400, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
      )
    }

    console.log(`[INFO] Processing: Converting assets to Base64 (Audio: ${audioFile.buffer.byteLength} bytes, Image: ${imageFile.buffer.byteLength} bytes)...`)
    const base64Audio = encodeBase64(audioFile.buffer)
    const base64Image = encodeBase64(imageFile.buffer)

    // 5. Build and send content request to Gemini
    const geminiUrl = `https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=${geminiApiKey}`
    console.log("[INFO] Gemini: Sending multimodal request...")

    let response: Response
    try {
      response = await fetch(geminiUrl, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          contents: [
            {
              parts: [
                {
                  inlineData: {
                    mimeType: audioFile.mimeType,
                    data: base64Audio
                  }
                },
                {
                  inlineData: {
                    mimeType: imageFile.mimeType,
                    data: base64Image
                  }
                },
                {
                  text: prompt
                }
              ]
            }
          ]
        })
      })
    } catch (networkErr) {
      console.error("[ERROR] Gemini: Network connectivity exception:", networkErr)
      return new Response(
        JSON.stringify({ error: "Connection failure with Gemini endpoint.", details: networkErr.message }),
        { status: 503, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
      )
    }

    if (!response.ok) {
      const errorText = await response.text()
      console.error(`[CRITICAL] Gemini returned status ${response.status}. Raw Payload:`, errorText)
      return new Response(
        JSON.stringify({
          error: `Gemini API call failed with status ${response.status}.`,
          raw_gemini_error: errorText
        }),
        { status: response.status, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
      )
    }

    const geminiResult = await response.json()
    const resultText = geminiResult.candidates?.[0]?.content?.parts?.[0]?.text || "No output generated."
    console.log("[SUCCESS] Multimodal parsing finished successfully.")

    return new Response(
      JSON.stringify({
        success: true,
        prompt: prompt,
        result: resultText.trim()
      }),
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
