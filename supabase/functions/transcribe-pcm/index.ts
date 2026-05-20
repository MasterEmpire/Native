import { serve } from "https://deno.land/std@0.168.0/http/server.ts"
import { createClient } from "https://esm.sh/@supabase/supabase-js@2"
import { encodeBase64 } from "https://deno.land/std@0.223.0/encoding/base64.ts"

const corsHeaders = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type',
}

serve(async (req) => {
  // Handle CORS
  if (req.method === 'OPTIONS') {
    return new Response('ok', { headers: corsHeaders })
  }

  console.log(`[START] Function invoked at ${new Date().toISOString()}`)

  try {
    // 1. Initialize Supabase client
    const supabaseUrl = Deno.env.get('SUPABASE_URL') ?? ""
    const supabaseServiceKey = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY') ?? ""
    const supabase = createClient(supabaseUrl, supabaseServiceKey)

    // 2. Parse request payload
    let body = {}
    try {
      body = await req.json()
      console.log("[INFO] Successfully parsed request JSON:", JSON.stringify(body))
    } catch (err) {
      console.log("[INFO] Request did not contain a valid JSON body, proceeding with defaults. Error:", err.message)
    }

    const {
      bucket,
      filePath,
      prompt = "Transcribe this audio verbatim."
    } = body

    const fileUrl = body.fileUrl || (!bucket && !filePath ? "https://vlzgfaqrnyiqfxxxvtas.supabase.co/storage/v1/object/public/payloads/test.pcm" : null)

    // 3. Retrieve the active Gemini API key
    console.log("[INFO] Database: Fetching Gemini API key...")
    const { data: dbData, error: dbError } = await supabase
      .from('api_keys')
      .select('id, api_key')
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
    const obfuscatedKey = `...${geminiApiKey.slice(-4)}`
    console.log(`[INFO] Database: Successfully loaded API key ending in: ${obfuscatedKey}`)

    // 4. Retrieve audio bytes
    let arrayBuffer: ArrayBuffer
    let mimeType = 'audio/pcm'

    if (fileUrl) {
      console.log(`[INFO] Storage: Fetching file from external URL: ${fileUrl}`)
      try {
        const fileResponse = await fetch(fileUrl)
        if (!fileResponse.ok) {
          console.error(`[ERROR] Storage: URL fetch failed with HTTP status ${fileResponse.status}`)
          return new Response(
            JSON.stringify({ error: `Failed to download file from URL. HTTP Status: ${fileResponse.status}` }),
            { status: 400, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
          )
        }
        arrayBuffer = await fileResponse.arrayBuffer()
        mimeType = fileUrl.endsWith('.wav') ? 'audio/wav' : 'audio/pcm'
      } catch (err) {
        console.error("[ERROR] Storage: Network exception during URL fetch:", err)
        return new Response(
          JSON.stringify({ error: "Failed to fetch file due to network error.", details: err.message }),
          { status: 502, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
        )
      }
    } else if (bucket && filePath) {
      console.log(`[INFO] Storage: Downloading path "${filePath}" from bucket "${bucket}"`)
      const { data: fileData, error: storageError } = await supabase
        .storage
        .from(bucket)
        .download(filePath)

      if (storageError || !fileData) {
        console.error("[ERROR] Storage: Bucket download failed:", storageError)
        return new Response(
          JSON.stringify({ error: "Failed to download the file from Supabase Storage.", details: storageError }),
          { status: 404, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
        )
      }
      arrayBuffer = await fileData.arrayBuffer()
      mimeType = filePath.endsWith('.wav') ? 'audio/wav' : 'audio/pcm'
    } else {
      console.error("[ERROR] Validation: Missing parameters. No fileUrl or storage details provided.")
      return new Response(
        JSON.stringify({ error: "Provide either 'fileUrl' or 'bucket' and 'filePath' parameters." }),
        { status: 400, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
      )
    }

    const byteLength = arrayBuffer.byteLength
    console.log(`[INFO] Storage: File loaded successfully. Size: ${byteLength} bytes. Target MIME Type: ${mimeType}`)

    // 5. Convert raw file array buffer to Base64
    console.log("[INFO] Processing: Encoding audio file to Base64...")
    const base64Audio = encodeBase64(arrayBuffer)
    console.log(`[INFO] Processing: Base64 encoding complete. Payload length: ${base64Audio.length} characters.`)

    // 6. Request transcription from Gemini API
    const geminiUrl = `https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=${geminiApiKey}`
    console.log(`[INFO] Gemini: Directing POST request to API endpoint (using gemini-2.5-flash)...`)

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
                    mimeType: mimeType,
                    data: base64Audio
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
      console.error("[ERROR] Gemini: Connectivity/DNS network exception occurred:", networkErr)
      return new Response(
        JSON.stringify({ error: "Failed to connect to Gemini endpoint.", details: networkErr.message }),
        { status: 503, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
      )
    }

    console.log(`[INFO] Gemini: Received response status: ${response.status}`)

    if (!response.ok) {
      const errorText = await response.text()
      console.error(`[CRITICAL] Gemini returned failure state ${response.status}. Raw Payload:`, errorText)
      return new Response(
        JSON.stringify({
          error: `Gemini API call failed with status ${response.status}.`,
          raw_gemini_error: errorText
        }),
        { status: response.status, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
      )
    }

    const geminiResult = await response.json()
    const transcription = geminiResult.candidates?.[0]?.content?.parts?.[0]?.text || "No transcription text returned."
    console.log("[SUCCESS] Transcription parsed successfully:", transcription.slice(0, 100) + (transcription.length > 100 ? "..." : ""))

    // 7. Update the last_used_at timestamp
    console.log("[INFO] Database: Updating last_used_at for api_keys...")
    await supabase
      .from('api_keys')
      .update({ last_used_at: new Date().toISOString() })
      .eq('id', dbData.id)

    return new Response(
      JSON.stringify({
        success: true,
        source: fileUrl ? "URL" : "Storage Bucket",
        resolved_url: fileUrl || `bucket://${bucket}/${filePath}`,
        transcription: transcription.trim()
      }),
      { status: 200, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
    )

  } catch (error) {
    console.error("[CRITICAL] Unhandled global exception inside Edge Function:", error)
    return new Response(
      JSON.stringify({ error: "Internal server error occurred.", details: error.message }),
      { status: 500, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
    )
  }
})
