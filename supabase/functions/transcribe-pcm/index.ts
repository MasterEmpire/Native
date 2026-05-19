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

  try {
    // 1. Initialize Supabase client using internal system environment variables
    const supabaseUrl = Deno.env.get('SUPABASE_URL') ?? ""
    const supabaseServiceKey = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY') ?? ""
    const supabase = createClient(supabaseUrl, supabaseServiceKey)

    // 2. Parse request payload
    let body = {}
    try {
      body = await req.json()
    } catch (_err) {
      // Fallback to empty body to allow GET-like trigger tests
    }

    const { 
      bucket,
      filePath,
      prompt = "Transcribe this audio verbatim."
    } = body

    // Use provided fileUrl, or fallback to the provided test target URL
    const fileUrl = body.fileUrl || (!bucket && !filePath ? "https://vlzgfaqrnyiqfxxxvtas.supabase.co/storage/v1/object/public/payloads/test.pcm" : null)

    // 3. Retrieve the active Gemini API key from your api_keys table
    const { data: dbData, error: dbError } = await supabase
      .from('api_keys')
      .select('api_key')
      .eq('service', 'gemini')
      .eq('is_active', true)
      .limit(1)
      .single()

    if (dbError || !dbData?.api_key) {
      return new Response(
        JSON.stringify({ error: "Could not retrieve an active Gemini API key.", details: dbError }),
        { status: 500, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
      )
    }

    const geminiApiKey = dbData.api_key

    // 4. Retrieve audio bytes (either from URL or Supabase Storage)
    let arrayBuffer: ArrayBuffer
    let mimeType = 'audio/pcm'

    if (fileUrl) {
      const fileResponse = await fetch(fileUrl)
      if (!fileResponse.ok) {
        return new Response(
          JSON.stringify({ error: `Failed to download file from URL: ${fileUrl}` }),
          { status: 400, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
        )
      }
      arrayBuffer = await fileResponse.arrayBuffer()
      mimeType = fileUrl.endsWith('.wav') ? 'audio/wav' : 'audio/pcm'
    } else if (bucket && filePath) {
      const { data: fileData, error: storageError } = await supabase
        .storage
        .from(bucket)
        .download(filePath)

      if (storageError || !fileData) {
        return new Response(
          JSON.stringify({ error: "Failed to download the file from Supabase Storage.", details: storageError }),
          { status: 404, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
        )
      }
      arrayBuffer = await fileData.arrayBuffer()
      mimeType = filePath.endsWith('.wav') ? 'audio/wav' : 'audio/pcm'
    } else {
      return new Response(
        JSON.stringify({ error: "Provide either 'fileUrl' or 'bucket' and 'filePath' parameters." }),
        { status: 400, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
      )
    }

    // 5. Convert raw file array buffer to Base64
    const base64Audio = encodeBase64(arrayBuffer)

    // 6. Request transcription from Gemini API (utilizing gemini-2.5-flash)
    const geminiUrl = `https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=${geminiApiKey}`

    const response = await fetch(geminiUrl, {
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

    if (!response.ok) {
      const errorText = await response.text()
      return new Response(
        JSON.stringify({ error: "Gemini API call failed.", details: errorText }),
        { status: response.status, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
      )
    }

    const geminiResult = await response.json()
    const transcription = geminiResult.candidates?.[0]?.content?.parts?.[0]?.text || "No transcription text returned."

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
    return new Response(
      JSON.stringify({ error: "Internal server error occurred.", details: error.message }),
      { status: 500, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
    )
  }
})