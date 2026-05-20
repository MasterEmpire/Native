import { serve } from "https://deno.land/std@0.168.0/http/server.ts"
import { createClient } from "https://esm.sh/@supabase/supabase-js@2"
import { encodeBase64 } from "https://deno.land/std@0.223.0/encoding/base64.ts"

const corsHeaders = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type',
}

// 1. Define Dummy Tools (Function Declarations)
const tools = [
  {
    functionDeclarations: [
      {
        name: "get_current_weather",
        description: "Fetch the current weather details for a specific city.",
        parameters: {
          type: "OBJECT",
          properties: {
            location: {
              type: "STRING",
              description: "The city name, e.g. London, Tokyo, Berlin"
            },
            unit: {
              type: "STRING",
              enum: ["celsius", "fahrenheit"],
              description: "The temperature scale to return."
            }
          },
          required: ["location"]
        }
      },
      {
        name: "control_smart_home_device",
        description: "Perform actions on connected home automation appliances like lights and air conditioners.",
        parameters: {
          type: "OBJECT",
          properties: {
            device: {
              type: "STRING",
              description: "The target appliance (e.g. living room fan, bedroom light, kitchen AC)"
            },
            action: {
              type: "STRING",
              enum: ["turn_on", "turn_off"],
              description: "The target power state."
            }
          },
          required: ["device", "action"]
        }
      }
    ]
  }
]

// 2. Hardcoded list of prompts to trigger both tool use and conversational responses
const testPrompts = [
  "What is the weather like in Tokyo right now?",
  "Turn off the kitchen AC immediately.",
  "Please describe the provided image in detail.",
  "Provide a transcription of the audio file."
]

serve(async (req) => {
  if (req.method === 'OPTIONS') {
    return new Response('ok', { headers: corsHeaders })
  }

  // Pick a random prompt from the list to test behavior dynamically
  const selectedPrompt = testPrompts[Math.floor(Math.random() * testPrompts.length)]
  console.log(`[START] Tool Use test with selected prompt: "${selectedPrompt}"`)

  try {
    // Initialize Supabase client
    const supabaseUrl = Deno.env.get('SUPABASE_URL') ?? ""
    const supabaseServiceKey = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY') ?? ""
    const supabase = createClient(supabaseUrl, supabaseServiceKey)

    const audioUrl = "https://vlzgfaqrnyiqfxxxvtas.supabase.co/storage/v1/object/public/payloads/test.pcm"
    const imageUrl = "https://vlzgfaqrnyiqfxxxvtas.supabase.co/storage/v1/object/public/payloads/test.png"

    // Fetch Gemini API key
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

    // Download assets concurrently
    const [audioRes, imageRes] = await Promise.all([
      fetch(audioUrl).then(res => res.arrayBuffer()),
      fetch(imageUrl).then(res => res.arrayBuffer())
    ])

    const base64Audio = encodeBase64(audioRes)
    const base64Image = encodeBase64(imageRes)

    // Send request including tool declarations to Gemini
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
                  mimeType: "audio/pcm",
                  data: base64Audio
                }
              },
              {
                inlineData: {
                  mimeType: "image/png",
                  data: base64Image
                }
              },
              {
                text: selectedPrompt
              }
            ]
          }
        ],
        tools: tools // Injecting the tools configuration
      })
    })

    if (!response.ok) {
      const errorText = await response.text()
      console.error(`[CRITICAL] Gemini returned status ${response.status}.`, errorText)
      return new Response(
        JSON.stringify({ error: "Gemini API call failed.", details: errorText }),
        { status: response.status, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
      )
    }

    const geminiResult = await response.json()
    const firstPart = geminiResult.candidates?.[0]?.content?.parts?.[0]

    // Check if Gemini invoked a function call instead of standard text generation
    if (firstPart && "functionCall" in firstPart) {
      console.log("[SUCCESS] Gemini triggered a tool execution request!", firstPart.functionCall)
      return new Response(
        JSON.stringify({
          success: true,
          execution_type: "TOOL_CALL",
          prompt_used: selectedPrompt,
          tool_call: firstPart.functionCall
        }),
        { status: 200, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
      )
    }

    // Return standard text response if no tool was matched
    const standardResultText = firstPart?.text || "No output generated."
    console.log("[SUCCESS] Gemini returned a conversational response.")

    return new Response(
      JSON.stringify({
        success: true,
        execution_type: "CONVERSATIONAL_RESPONSE",
        prompt_used: selectedPrompt,
        result: standardResultText.trim()
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
