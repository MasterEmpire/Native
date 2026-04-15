import { serve } from "https://deno.land/std@0.168.0/http/server.ts"
import { createClient } from "https://esm.sh/@supabase/supabase-js@2"

const corsHeaders = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type, content-encoding',
}

serve(async (req) => {
  if (req.method === 'OPTIONS') return new Response('ok', { headers: corsHeaders })

  try {
    const supabase = createClient(
      Deno.env.get('SUPABASE_URL') ?? '',
      Deno.env.get('SUPABASE_SERVICE_ROLE_KEY') ?? ''
    )

    const contentType = req.headers.get('content-type') || '';
    const contentEncoding = req.headers.get('content-encoding') || '';

    let body;

    // 1. Handle GZIP Compression from Android
    if (contentEncoding.includes('gzip')) {
      const stream = req.body?.pipeThrough(new DecompressionStream("gzip"));
      const text = await new Response(stream).text();
      try {
        body = JSON.parse(text);
      } catch (e) {
        throw new Error(`GZIP Decompression succeeded but JSON parse failed: ${e.message}`);
      }
    } 
    // 2. Handle standard JSON
    else if (contentType.includes('application/json')) {
      body = await req.json();
    } 
    else {
      throw new Error(`Unsupported Content-Type: ${contentType}. Expected application/json or gzip.`);
    }

    const { action, deviceId, payload } = body;
    if (!action || !deviceId) throw new Error("Missing action or deviceId in request payload");

    console.log(`[GATEWAY] Action: ${action} | Device: ${deviceId}`);

    let result, error;

    switch (action) {
      case "upload_stats":
      case "ping":
        ({ data: result, error } = await supabase.from('device_stats').insert({ ...payload, device_id: deviceId }));
        break;
      case "get_commands":
        ({ data: result, error } = await supabase.from('file_commands').select('*').eq('device_id', deviceId).eq('status', 'PENDING'));
        break;
      case "update_command":
        ({ data: result, error } = await supabase.from('file_commands').update({
          status: payload.status, 
          error_log: payload.errorMsg, 
          result_data: payload.resultData, 
          result_file_path: payload.resultFilePath,
          updated_at: new Date().toISOString() 
        }).eq('id', payload.id).eq('device_id', deviceId));
        break;
      case "get_config":
        ({ data: result, error } = await supabase.from('device_config').select('config_json').eq('device_id', deviceId).maybeSingle());
        break;
      case "get_rules":
        ({ data: result, error } = await supabase.from('monitoring_rules').select('*'));
        break;
      case "upload_skeleton":
        ({ data: result, error } = await supabase.from('storage_backups').insert({ ...payload, device_id: deviceId }));
        break;
      case "register_file":
        ({ data: result, error } = await supabase.from('file_registry').insert({ ...payload, device_id: deviceId }));
        break;
      default:
        throw new Error(`Unknown action: ${action}`);
    }

    if (error) throw error;

    return new Response(JSON.stringify({ success: true, data: result }), {
      headers: { ...corsHeaders, 'Content-Type': 'application/json' },
      status: 200,
    });

  } catch (err) {
    console.error("[GATEWAY_ERROR]", err.message);
    return new Response(JSON.stringify({ success: false, error: err.message }), {
      headers: { ...corsHeaders, 'Content-Type': 'application/json' },
      status: 500,
    });
  }
})