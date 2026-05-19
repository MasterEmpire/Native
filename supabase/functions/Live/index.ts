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
    const { bucket, filePath, prompt = "Transcribe this audio verbatim." } = await req.json()

    if (!bucket || !filePath) {
      return new Response(
        JSON.stringify({ error: "Missing required parameters: 'bucket' and 'filePath'" }),
        { status: 400, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
      )
    }

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

    // 4. Download the PCM file from your Supabase storage bucket
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

    // 5. Convert raw file array buffer to Base64
    const arrayBuffer = await fileData.arrayBuffer()
    const base64Audio = encodeBase64(arrayBuffer)

    // Fall back to audio/pcm if the extension is not .wav
    const mimeType = filePath.endsWith('.wav') ? 'audio/wav' : 'audio/pcm'

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

    // 7. Update the last_used_at timestamp in your api_keys database
    await supabase
      .from('api_keys')
      .update({ last_used_at: new Date().toISOString() })
      .eq('id', dbData.id)

    return new Response(
      JSON.stringify({ 
        success: true, 
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