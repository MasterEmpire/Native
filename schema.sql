-- AUTO-GENERATED SCHEMA DUMP
-- Date: 2026-07-19T21:09:44.018Z

-- ========================
-- TABLES & COLUMNS
-- ========================
Table: api_keys
is_active (boolean), last_used_at (timestamp with time zone), cooldown_until (timestamp with time zone), service (text), api_key (text), name (text), id (bigint), created_at (timestamp with time zone)

Table: book_pages
content_json (jsonb), page_key (text), manual_flag (text), id (uuid), book_id (uuid), page_number (integer), created_at (timestamp with time zone)

Table: book_question_links
created_at (timestamp with time zone), id (uuid), chunk_id (uuid), question_id (uuid), similarity_score (double precision)

Table: books
id (uuid), created_at (timestamp with time zone), toc (jsonb), course_code (text), page_offset (integer), title (text), category (text), cover_url (text), author (text)

Table: campus_channels
created_at (timestamp with time zone), channel_handle (text), last_extracted_at (timestamp with time zone), members_data (jsonb), is_private (boolean), telegram_peer_id (bigint), is_active (boolean), last_scraped_id (bigint), id (uuid)

Table: campus_feed
image_url (text), full_text (text), sender_id (bigint), channel_handle (text), sender_name (text), sender_username (text), created_at (timestamp with time zone), id (uuid), telegram_timestamp (timestamp with time zone), metadata (jsonb), telegram_id (bigint)

Table: chunks
id (uuid), chunk_text (text), embedding (USER-DEFINED), created_at (timestamp with time zone), document_id (uuid), page_number (integer), chunk_index (integer), next_chunk_id (uuid), prev_chunk_id (uuid), toc_node_id (uuid)

Table: conduit_favorites
target_id (text), category (text), repo_name (text), id (uuid), metadata (jsonb), created_at (timestamp with time zone)

Table: conduit_history
title (text), ops (jsonb), conduit_id (integer), created_at (timestamp with time zone), repo_name (text), sha (text), id (uuid), note (text), meta (text), type (text)

Table: conduit_logs
data (jsonb), id (uuid), created_at (timestamp with time zone), repo_name (text), type (text)

Table: conversation_members
user_id (uuid), id (uuid), last_read_at (timestamp with time zone), role (USER-DEFINED), conversation_id (uuid), created_at (timestamp with time zone), muted_until (timestamp with time zone)

Table: conversations
created_at (timestamp with time zone), id (uuid), avatar_url (text), title (character varying), owner_id (uuid), metadata (jsonb), last_message_at (timestamp with time zone), type (USER-DEFINED)

Table: courses
created_at (timestamp with time zone), department_id (uuid), name (text), code (text), id (uuid)

Table: departments
created_at (timestamp with time zone), id (uuid), name (text)

Table: documents
status (text), chunk_count (integer), storage_path (text), user_id (uuid), page_count (integer), id (uuid), last_processed_at (timestamp with time zone), file_name (text), created_at (timestamp with time zone)

Table: embedding_progress
updated_at (timestamp with time zone), error_message (text), status (text), id (uuid), book_id (uuid), page_number (integer), block_index (integer), locked_until (timestamp with time zone)

Table: exams
media_summary (jsonb), program (text), general_instructions (text), exam_quality_notes (jsonb), constants_provided (jsonb), exam_type (text), date (text), total_marks (numeric), created_at (timestamp with time zone), course_id (uuid), time_allowed_minutes (integer), university_id (uuid), id (uuid)

Table: extracted_events
id (uuid), channel_id (uuid), event_date (timestamp with time zone), source_ids (ARRAY), is_active (boolean), created_at (timestamp with time zone), title (text), description (text), event_type (text)

Table: featured_events
created_at (timestamp with time zone), id (uuid), external_url (text), html_content (text), action_type (text), button_color (text), button_text (text), tag_color (text), title (text), body (text), image_url (text), tag_text (text), app_route (jsonb), is_active (boolean), metadata (jsonb)

Table: linkoin_transactions
transaction_type (text), idempotency_key (text), description (text), created_at (timestamp with time zone), amount (integer), user_id (uuid), id (uuid)

Table: live_stage_questions
text (text), id (uuid), conversation_id (uuid), sender_id (uuid), created_at (timestamp with time zone), is_pinned (boolean), status (text)

Table: live_study_sessions
active_user_ids (ARRAY), conversation_id (uuid), generation_state (text), course_name (text), id (uuid), lesson_topic (text), raw_source_text (text), layout_blueprint (jsonb), compiled_answers (jsonb), lecture_chunks (jsonb), last_updated_at (timestamp with time zone)

Table: messages
forward_meta (jsonb), conversation_id (uuid), reply_to_id (uuid), id (uuid), text (text), is_edited (boolean), created_at (timestamp with time zone), attachments (jsonb), sender_id (uuid)

Table: migration_progress
id (uuid), processed_at (timestamp with time zone), remote_id (text), pdf_name (text), page_index (text), status (text), error_message (text)

Table: migration_sync_state
current_offset (integer), id (integer), last_run_at (timestamp with time zone)

Table: news_feed
snippet (text), channel (text), created_at (timestamp with time zone), telegram_timestamp (timestamp with time zone), telegram_id (bigint), id (bigint), post_url (text), image_url (text), full_text (text), title (text)

Table: notifications
id (uuid), is_read (boolean), action_data (jsonb), created_at (timestamp with time zone), icon (text), insight (text), description (text), title (text), type (text), user_id (uuid)

Table: peer_questions
body (text), id (uuid), user_id (uuid), created_at (timestamp with time zone), replies_count (integer), course_tag (text), title (text)

Table: poll_votes
user_id (uuid), id (uuid), created_at (timestamp with time zone), option_index (integer), message_id (uuid)

Table: profiles
department (text), last_username_change_at (timestamp with time zone), university_id (uuid), last_seen_at (timestamp with time zone), updated_at (timestamp with time zone), linkoin_balance (integer), id (uuid), full_name (text), avatar_url (text), level (text), username (text), telegram_id (bigint), freshman_stream (text), year (text), target_department (text), program (text), phone (text), bio (text), theme (text), telegram_username (text), class_id (uuid), last_streak_update (date), longest_streak (integer), current_streak (integer), registered_with_telegram (boolean)

Table: question_book_mappings
snippet (text), processed_at (timestamp with time zone), page_key (text), status (text), content_index (integer), error_message (text), is_valid (boolean), book_id (uuid), question_id (uuid), id (uuid), created_at (timestamp with time zone)

Table: question_processing_progress
created_at (timestamp with time zone), question_id (uuid), book_id (uuid), processed_at (timestamp with time zone), error_message (text), status (text)

Table: question_reports
created_at (timestamp with time zone), report_text (text), id (uuid), status (text), source (text), question_id (uuid)

Table: questions
transcription_quality (jsonb), media (jsonb), matching_data (jsonb), options (jsonb), id (uuid), points (numeric), retry_count (integer), created_at (timestamp with time zone), question_order (integer), correct_answer (jsonb), embedding_status (text), section_id (uuid), question_number (text), question_type (text), embedding (USER-DEFINED), text (text), explanation (text)

Table: referrals
referrer_id (uuid), id (uuid), referee_id (uuid), status (text), created_at (timestamp with time zone)

Table: sections
id (uuid), instructions (text), title (text), created_at (timestamp with time zone), section_order (integer), shared_context (jsonb), total_points (numeric), exam_id (uuid)

Table: squad_bans
banned_until (timestamp with time zone), id (uuid), user_id (uuid), created_at (timestamp with time zone), conversation_id (uuid)

Table: system_config
key (text), value (jsonb)

Table: telegram_login_tokens
telegram_id (bigint), id (uuid), created_at (timestamp with time zone), expires_at (timestamp with time zone), metadata (jsonb), token_hash (text)

Table: universities
name (text), short_name (text), id (uuid), created_at (timestamp with time zone)

-- ========================
-- RLS POLICIES
-- ========================
Table: profiles | Policy: Allow update for owners | Cmd: UPDATE | Using: (auth.uid() = id)
Table: conversations | Policy: Conversations visibility | Cmd: SELECT | Using: is_member_of(id)
Table: conversation_members | Policy: Members visibility | Cmd: SELECT | Using: (is_member_of(conversation_id) OR (user_id = auth.uid()))
Table: messages | Policy: Messages visibility | Cmd: SELECT | Using: is_member_of(conversation_id)
null
Table: messages | Policy: Users can update their own messages | Cmd: UPDATE | Using: (auth.uid() = sender_id)
Table: messages | Policy: Users can delete their own messages | Cmd: DELETE | Using: (auth.uid() = sender_id)
Table: news_feed | Policy: Allow public read access | Cmd: SELECT | Using: true
Table: conversations | Policy: Owners can update their squads | Cmd: UPDATE | Using: (auth.uid() = owner_id)
Table: conversations | Policy: Owners can delete their squads | Cmd: DELETE | Using: (auth.uid() = owner_id)
Table: messages | Policy: Dynamic Read Access for Messages | Cmd: SELECT | Using: ((EXISTS ( SELECT 1
   FROM conversation_members
  WHERE ((conversation_members.conversation_id = messages.conversation_id) AND (conversation_members.user_id = auth.uid())))) OR (EXISTS ( SELECT 1
   FROM conversations
  WHERE ((conversations.id = messages.conversation_id) AND ((conversations.metadata ->> 'privacy'::text) = 'public'::text)))))
Table: conversations | Policy: Dynamic Read Access for Conversations | Cmd: SELECT | Using: (((metadata ->> 'privacy'::text) = 'public'::text) OR (auth.uid() = owner_id) OR (EXISTS ( SELECT 1
   FROM conversation_members
  WHERE ((conversation_members.conversation_id = conversations.id) AND (conversation_members.user_id = auth.uid())))))
Table: books | Policy: Auth read only | Cmd: SELECT | Using: (auth.role() = 'authenticated'::text)
Table: book_pages | Policy: Auth read only | Cmd: SELECT | Using: (auth.role() = 'authenticated'::text)
Table: exams | Policy: Auth read only | Cmd: SELECT | Using: (auth.role() = 'authenticated'::text)
Table: questions | Policy: Auth read only | Cmd: SELECT | Using: (auth.role() = 'authenticated'::text)
Table: sections | Policy: Auth read only | Cmd: SELECT | Using: (auth.role() = 'authenticated'::text)
Table: courses | Policy: Auth read only | Cmd: SELECT | Using: (auth.role() = 'authenticated'::text)
null
null
Table: profiles | Policy: Sensitive data visibility | Cmd: SELECT | Using: (auth.uid() = id)
Table: profiles | Policy: Users can delete their own profile | Cmd: DELETE | Using: (auth.uid() = id)
Table: conversation_members | Policy: Users can update their own conversation member status | Cmd: UPDATE | Using: (auth.uid() = user_id)
null
Table: peer_questions | Policy: Public read peer_questions | Cmd: SELECT | Using: true
null
Table: notifications | Policy: Users can read own notifications | Cmd: SELECT | Using: (auth.uid() = user_id)
Table: notifications | Policy: Users can update own notifications | Cmd: UPDATE | Using: (auth.uid() = user_id)
Table: featured_events | Policy: Public read featured_events | Cmd: SELECT | Using: (is_active = true)
Table: live_study_sessions | Policy: Public read active sessions | Cmd: SELECT | Using: true
null
null
null
Table: live_stage_questions | Policy: Hostess can update live questions | Cmd: UPDATE | Using: (auth.uid() = ( SELECT ((conversations.metadata ->> 'live_host_id'::text))::uuid AS uuid
   FROM conversations
  WHERE (conversations.id = live_stage_questions.conversation_id)))
Table: live_stage_questions | Policy: Hostess can delete live questions | Cmd: DELETE | Using: (auth.uid() = ( SELECT ((conversations.metadata ->> 'live_host_id'::text))::uuid AS uuid
   FROM conversations
  WHERE (conversations.id = live_stage_questions.conversation_id)))
Table: messages | Policy: Admins and Owners can delete any group messages | Cmd: DELETE | Using: (EXISTS ( SELECT 1
   FROM conversation_members cm
  WHERE ((cm.conversation_id = messages.conversation_id) AND (cm.user_id = auth.uid()) AND (cm.role = ANY (ARRAY['owner'::member_role, 'admin'::member_role])))))
Table: live_study_sessions | Policy: Hosts can manage live sessions | Cmd: ALL | Using: (EXISTS ( SELECT 1
   FROM conversation_members cm
  WHERE ((cm.conversation_id = live_study_sessions.conversation_id) AND (cm.user_id = auth.uid()) AND (cm.role = ANY (ARRAY['owner'::member_role, 'admin'::member_role])))))
Table: live_stage_questions | Policy: Members can read live questions | Cmd: SELECT | Using: is_member_of(conversation_id)
Table: poll_votes | Policy: Public read for poll votes | Cmd: SELECT | Using: true
Table: linkoin_transactions | Policy: Users can view their own transactions | Cmd: SELECT | Using: (auth.uid() = user_id)
Table: referrals | Policy: Users can view their own referrals | Cmd: SELECT | Using: ((auth.uid() = referrer_id) OR (auth.uid() = referee_id))

-- ========================
-- FUNCTIONS & RPCs
-- ========================
-- Function: get_table_counts

DECLARE
    r RECORD;
BEGIN
    FOR r IN
        SELECT t.table_name
        FROM information_schema.tables t
        WHERE t.table_schema = 'public'
          AND t.table_name IN (
            'amharic_dictionary_final',
            'purified_amharic_dictionary',
            'flat_source_metadata',
            'union_refined_dictionary',
            'processed_words',
            'candidate_words',
            'candidate_words_imp6',
            'tele_analysis',
            'verse_analysis',
            'lonely_roots_inspection'
          )
    LOOP
        EXECUTE format(
            'SELECT %L, count(*) FROM public.%I',
            r.table_name, r.table_name
        )
        INTO table_name, row_count;

        RETURN NEXT;
    END LOOP;
END;


-- Function: get_pending_questions_for_mapping

BEGIN
    RETURN QUERY
    SELECT q.id, q.text, q.question_type, q.options, q.matching_data
    FROM questions q
    JOIN sections s ON q.section_id = s.id
    JOIN exams e ON s.exam_id = e.id
    WHERE e.course_id = p_course_id
      AND NOT EXISTS (
          SELECT 1 
          FROM question_book_mappings qbm 
          WHERE qbm.question_id = q.id 
            AND qbm.book_id = p_book_id 
            AND qbm.status IN ('completed', 'processing')
      )
    ORDER BY q.created_at ASC
    LIMIT p_limit;
END;


-- Function: lease_gemini_api_key

DECLARE
    selected_id bigint;
BEGIN
    SELECT k.id INTO selected_id
    FROM api_keys k
    WHERE k.service = 'gemini'
      AND k.is_active = true
      AND (k.cooldown_until IS NULL OR k.cooldown_until <= NOW())
    ORDER BY k.last_used_at ASC NULLS FIRST
    LIMIT 1
    FOR UPDATE SKIP LOCKED;

    IF selected_id IS NOT NULL THEN
        UPDATE api_keys AS ak
        SET last_used_at = NOW()
        WHERE ak.id = selected_id;

        RETURN QUERY 
        SELECT k.id, k.api_key
        FROM api_keys k
        WHERE k.id = selected_id;
    END IF;
END;


-- Function: complete_embedding_job

BEGIN
    UPDATE public.embedding_progress
    SET status = 'completed',
        locked_until = NULL,
        error_message = NULL,
        updated_at = now()
    WHERE id = p_job_id;
END;


-- Function: fail_embedding_job

BEGIN
    UPDATE public.embedding_progress
    SET status = 'failed',
        locked_until = NULL,
        error_message = p_error,
        updated_at = now()
    WHERE id = p_job_id;
END;


-- Function: find_user_by_any_identity

BEGIN
    RETURN QUERY
    SELECT p.id, p.full_name, p.username, p.avatar_url
    FROM public.profiles p
    WHERE p.id != req_user_id 
    AND p.username ILIKE (search_term || '%')
    LIMIT 5;
END;


-- Function: initialize_book_embedding_jobs

DECLARE
    v_inserted_rows integer := 0;
BEGIN
    -- 1. Clear out any stale records for this book to start fresh
    DELETE FROM public.embedding_progress WHERE book_id = p_book_id;

    -- 2. Extract array blocks natively using WITH ORDINALITY
    INSERT INTO public.embedding_progress (book_id, page_number, block_index, status)
    SELECT 
        p_book_id,
        bp.page_number,
        arr.idx - 1 as block_index, -- Convert 1-based ordinality index to 0-based block index
        'pending'::text
    FROM 
        public.book_pages bp
    CROSS JOIN LATERAL 
        jsonb_array_elements(bp.content_json) WITH ORDINALITY arr(elem, idx)
    WHERE 
        bp.book_id = p_book_id
        AND jsonb_typeof(bp.content_json) = 'array'; -- Safe-guard: ignore malformed columns

    GET DIAGNOSTICS v_inserted_rows = ROW_COUNT;
    RETURN v_inserted_rows;
END;


-- Function: squad_kick_member

DECLARE
    executor_role text;
    target_role text;
BEGIN
    -- 1. Get Executor Role
    SELECT role INTO executor_role FROM public.conversation_members 
    WHERE conversation_id = req_conv_id AND user_id = auth.uid();

    IF executor_role NOT IN ('owner', 'admin') THEN
        RAISE EXCEPTION 'Access Denied: Administrative privileges required.';
    END IF;

    -- 2. Get Target Role
    SELECT role INTO target_role FROM public.conversation_members 
    WHERE conversation_id = req_conv_id AND user_id = req_target_id;

    -- 3. Enforce Hierarchy
    IF target_role = 'owner' THEN
        RAISE EXCEPTION 'Mutiny Prevented: You cannot kick the group owner.';
    END IF;
    IF target_role = 'admin' AND executor_role != 'owner' THEN
        RAISE EXCEPTION 'Hierarchy Violation: Only the owner can kick an admin.';
    END IF;

    DELETE FROM public.conversation_members WHERE conversation_id = req_conv_id AND user_id = req_target_id;
END;


-- Function: cooldown_api_key

BEGIN
    UPDATE public.api_keys
    SET cooldown_until = (now() + interval '5 minutes')
    WHERE id = p_key_id;
END;


-- Function: acquire_embedding_jobs

DECLARE
    v_now timestamp with time zone := now();
BEGIN
    -- Safeguard: Ensure there is at least one active key not in cooldown
    IF NOT EXISTS (
        SELECT 1 FROM public.api_keys 
        WHERE service = 'gemini' AND is_active = true 
          AND (cooldown_until IS NULL OR cooldown_until::timestamp with time zone < v_now)
    ) THEN
        RAISE EXCEPTION 'No active, non-cooled-down Gemini API keys available in key pool.';
    END IF;

    RETURN QUERY
    WITH 
    -- 1. Sort active keys by least recently used
    active_keys AS (
        SELECT 
            id, 
            ak.api_key,
            row_number() OVER (ORDER BY last_used_at ASC NULLS FIRST) - 1 as seq_id,
            count(*) OVER () as total_keys
        FROM public.api_keys ak
        WHERE service = 'gemini' 
          AND is_active = true 
          AND (cooldown_until IS NULL OR cooldown_until::timestamp with time zone < v_now)
    ),
    -- 2. STAGE 1: Lock a batch of blocks using SKIP LOCKED (No window functions here)
    raw_jobs AS (
        SELECT 
            ep.id,
            ep.page_number,
            ep.block_index
        FROM public.embedding_progress ep
        WHERE ep.book_id = p_book_id
          AND (ep.status = 'pending' OR ep.status = 'failed' OR (ep.status = 'processing' AND ep.locked_until < v_now))
        ORDER BY ep.page_number, ep.block_index
        LIMIT p_batch_size
        FOR UPDATE SKIP LOCKED
    ),
    -- 3. STAGE 2: Safely apply window indexing over the locked rows
    locked_jobs AS (
        SELECT 
            rj.id,
            rj.page_number,
            rj.block_index,
            row_number() OVER (ORDER BY rj.page_number, rj.block_index) - 1 as seq_id
        FROM raw_jobs rj
    ),
    -- 4. Mark the locked blocks as processing in the DB for 5 minutes
    update_jobs AS (
        UPDATE public.embedding_progress ep
        SET status = 'processing',
            locked_until = v_now + interval '5 minutes',
            updated_at = v_now
        FROM raw_jobs rj
        WHERE ep.id = rj.id
    ),
    -- 5. Join jobs and keys using modulo mapping
    mapped_assignments AS (
        SELECT 
            lj.id as job_id,
            lj.page_number,
            lj.block_index,
            ak.api_key,
            ak.id as api_key_id
        FROM locked_jobs lj
        JOIN active_keys ak ON (lj.seq_id % ak.total_keys) = ak.seq_id
    ),
    -- 6. Update last_used_at on the keys to rotate them instantly
    update_keys AS (
        UPDATE public.api_keys ak
        SET last_used_at = v_now
        FROM (SELECT DISTINCT ma.api_key_id FROM mapped_assignments ma) u
        WHERE ak.id = u.api_key_id
    )
    SELECT 
        ma.job_id,
        ma.page_number,
        ma.block_index,
        ma.api_key,
        ma.api_key_id
    FROM mapped_assignments ma;
END;


-- Function: update_conv_last_message

BEGIN
  UPDATE public.conversations 
  SET last_message_at = NEW.created_at 
  WHERE id = NEW.conversation_id;
  RETURN NEW;
END;


-- Function: is_member_of

BEGIN
  RETURN EXISTS (
    SELECT 1 FROM public.conversation_members
    WHERE conversation_id = conv_id AND user_id = auth.uid()
  );
END;


-- Function: handle_new_user

DECLARE
    v_phone text;
BEGIN
    -- Extract and Strictly Normalize Phone at the DB layer
    v_phone := COALESCE(new.phone, new.raw_user_meta_data->>'phone');
    IF v_phone IS NOT NULL THEN
        v_phone := replace(v_phone, ' ', '');
        IF v_phone LIKE '0%' THEN
            v_phone := '+251' || substring(v_phone from 2);
        ELSIF v_phone NOT LIKE '+%' THEN
            v_phone := '+' || v_phone;
        END IF;
    END IF;

    INSERT INTO public.profiles (
        id, 
        full_name, 
        avatar_url, 
        username,
        telegram_id,
        telegram_username,
        registered_with_telegram,
        phone,
        level, 
        linkoin_balance
    )
    VALUES (
        new.id,
        COALESCE(new.raw_user_meta_data->>'full_name', 'New Scholar'),
        new.raw_user_meta_data->>'avatar_url',
        COALESCE(new.raw_user_meta_data->>'username', null),
        
        -- NEVER TRUST CLIENT FOR SECURE IDENTITY MAPPING
        NULL,  -- telegram_id
        NULL,  -- telegram_username
        false, -- registered_with_telegram
        
        v_phone,
        'Division I',
        100
    );
    RETURN new;
END;


-- Function: check_self_reply

DECLARE
    target_sender_id uuid;
BEGIN
    -- If there's no reply, just allow it
    IF NEW.reply_to_id IS NULL THEN
        RETURN NEW;
    END IF;

    -- Look up the sender of the original message
    SELECT sender_id INTO target_sender_id FROM public.messages WHERE id = NEW.reply_to_id;

    -- Compare
    IF target_sender_id = NEW.sender_id THEN
        RAISE EXCEPTION 'You cannot reply to your own messages. That is just sad.';
    END IF;

    RETURN NEW;
END;


-- Function: check_username_available

BEGIN
  RETURN NOT EXISTS (SELECT 1 FROM public.profiles WHERE username = req_username);
END;


-- Function: check_email_provider

DECLARE
    found_provider TEXT;
BEGIN
    -- Look into the private auth.users table safely
    SELECT (raw_app_meta_data->>'provider') INTO found_provider
    FROM auth.users
    WHERE email = req_email
    LIMIT 1;

    IF found_provider IS NOT NULL THEN
        RETURN QUERY SELECT TRUE, found_provider;
    ELSE
        RETURN QUERY SELECT FALSE, NULL::TEXT;
    END IF;
END;


-- Function: get_user_profile_public

DECLARE
    res jsonb;
BEGIN
    SELECT jsonb_build_object(
        'id', p.id,
        'full_name', p.full_name,
        'username', p.username,
        'avatar_url', p.avatar_url,
        'department', p.department,
        'level', p.level,
        'bio', p.bio
    ) INTO res
    FROM public.profiles p
    WHERE p.id = target_user_id;
    
    RETURN res;
END;


-- Function: get_public_profiles

BEGIN
    RETURN QUERY
    SELECT p.id, p.full_name, p.avatar_url, p.username, p.department, p.level
    FROM public.profiles p
    WHERE p.id = ANY(user_ids);
END;


-- Function: atomic_unpin_question

BEGIN
    -- If a question is being pinned, unpin all others in this specific live session
    IF NEW.is_pinned = true THEN
        UPDATE public.live_stage_questions
        SET is_pinned = false
        WHERE conversation_id = NEW.conversation_id 
          AND id != NEW.id 
          AND is_pinned = true;
    END IF;
    RETURN NEW;
END;


-- Function: heartbeat_live_session

DECLARE
    v_role text;
    v_metadata jsonb;
BEGIN
    -- Verify the requester's rank in the conversation
    SELECT role INTO v_role 
    FROM public.conversation_members 
    WHERE conversation_id = conv_id AND user_id = req_host_id;

    IF v_role IS NULL OR v_role NOT IN ('owner', 'admin') THEN
        RAISE EXCEPTION 'Access Denied: Only group owners or admins are authorized to host live sessions.';
    END IF;

    -- Fetch current metadata
    SELECT metadata INTO v_metadata FROM public.conversations WHERE id = conv_id;

    -- Initialize live_started_at with the current timestamp ONLY on fresh session starts
    IF NOT (v_metadata ? 'live_started_at') THEN
        v_metadata := jsonb_set(COALESCE(v_metadata, '{}'::jsonb), '{live_started_at}', to_jsonb(now()));
    END IF;

    -- Apply standard live status & heartbeat updates
    v_metadata := jsonb_set(
        jsonb_set(
            jsonb_set(v_metadata, '{is_live}', 'true'::jsonb),
            '{live_host_id}', to_jsonb(req_host_id::text)
        ),
        '{live_status}', '"active"'::jsonb
    );
    
    v_metadata := jsonb_set(v_metadata, '{live_heartbeat}', to_jsonb(now()));

    UPDATE public.conversations 
    SET metadata = v_metadata
    WHERE id = conv_id AND (
        (metadata->>'live_host_id' IS NULL) OR 
        (metadata->>'live_host_id' = req_host_id::text)
    );
END;


-- Function: force_peer_question_defaults_fn

BEGIN
    IF auth.role() = 'authenticated' THEN
        -- Force identity alignment
        NEW.user_id := auth.uid();
    END IF;
    RETURN NEW;
END;


-- Function: unpin_on_message_delete_fn

BEGIN
    UPDATE public.conversations
    SET metadata = metadata - 'pinned_message'
    WHERE id = OLD.conversation_id
      AND metadata->'pinned_message'->>'id' = OLD.id::text;
    RETURN OLD;
END;


-- Function: get_next_api_key

BEGIN
  RETURN QUERY
  SELECT ak.id, ak.api_key
  FROM api_keys ak
  WHERE ak.service = target_service
    AND ak.is_active = true
    AND (ak.cooldown_until IS NULL OR ak.cooldown_until <= NOW())
  ORDER BY ak.last_used_at ASC NULLS FIRST
  LIMIT 1;
END;


-- Function: mark_key_usage

BEGIN
  UPDATE api_keys 
  SET last_used_at = NOW() 
  WHERE id = key_id;
END;


-- Function: set_key_cooldown_rpc

BEGIN
  UPDATE api_keys 
  SET cooldown_until = NOW() + interval '5 minutes' 
  WHERE id = key_id;
END;


-- Function: protect_member_roles

BEGIN
    -- Only apply restrictions to API calls made by users (not server-side scripts)
    IF auth.role() = 'authenticated' THEN
        -- Check if either the role or the mute status is being modified
        IF NEW.role IS DISTINCT FROM OLD.role OR NEW.muted_until IS DISTINCT FROM OLD.muted_until THEN
            -- Only allow the modification if the user performing the action is an admin/owner
            IF NOT EXISTS (
                SELECT 1 FROM public.conversation_members 
                WHERE conversation_id = NEW.conversation_id 
                  AND user_id = auth.uid() 
                  AND role IN ('owner', 'admin')
            ) THEN
                RAISE EXCEPTION 'Security Violation: You do not have permission to alter roles or mute durations.';
            END IF;
        END IF;
    END IF;
    RETURN NEW;
END;


-- Function: get_or_create_notes

DECLARE
    conv_id uuid;
BEGIN
    IF req_user_id != auth.uid() THEN
        RAISE EXCEPTION 'Access Denied: You cannot create notes for another user.';
    END IF;

    SELECT c.id INTO conv_id
    FROM public.conversations c
    JOIN public.conversation_members cm ON c.id = cm.conversation_id
    WHERE c.type = 'notes' AND cm.user_id = req_user_id
    LIMIT 1;

    IF conv_id IS NULL THEN
        INSERT INTO public.conversations (type, title, owner_id) VALUES ('notes', 'My Notes', req_user_id) RETURNING id INTO conv_id;
        INSERT INTO public.conversation_members (conversation_id, user_id, role) VALUES (conv_id, req_user_id, 'admin');
    END IF;

    RETURN conv_id;
END;


-- Function: protect_profile_fields

BEGIN
    IF auth.role() = 'authenticated' THEN
        -- Protected Gamification
        NEW.linkoin_balance = OLD.linkoin_balance;
        NEW.level = OLD.level;
        
        -- Protected Identity & Telegram Trust
        -- If a hacker tries a direct API PATCH request, we overwrite it with the old secure value
        NEW.telegram_id = OLD.telegram_id;
        NEW.telegram_username = OLD.telegram_username;
        NEW.registered_with_telegram = OLD.registered_with_telegram;

        -- Phone Normalization (Enforce consistency on any client updates)
        IF NEW.phone IS NOT NULL THEN
            NEW.phone := replace(NEW.phone, ' ', '');
            IF NEW.phone LIKE '0%' THEN
                NEW.phone := '+251' || substring(NEW.phone from 2);
            ELSIF NEW.phone NOT LIKE '+%' THEN
                NEW.phone := '+' || NEW.phone;
            END IF;
        END IF;
    END IF;
    RETURN NEW;
END;


-- Function: sync_squad_privacy_slug

DECLARE
    base_slug text;
    candidate_slug text;
    counter integer := 1;
BEGIN
    -- If switched to private: Vaporize the slug
    IF NEW.metadata->>'privacy' = 'private' THEN
        NEW.metadata := NEW.metadata - 'slug';
        
    -- If switched to public: Generate a fresh secure slug
    ELSIF (NEW.metadata->>'privacy' = 'public' OR NEW.metadata->>'privacy' IS NULL) AND NOT (NEW.metadata ? 'slug') THEN
        base_slug := regexp_replace(lower(NEW.title), '[^a-z0-9]', '', 'g');
        IF base_slug = '' THEN base_slug := 'squad'; END IF;
        candidate_slug := base_slug;

        WHILE EXISTS (SELECT 1 FROM public.conversations WHERE id != NEW.id AND metadata->>'slug' = candidate_slug) LOOP
            candidate_slug := base_slug || counter::text;
            counter := counter + 1;
        END LOOP;
        
        NEW.metadata := NEW.metadata || jsonb_build_object('slug', candidate_slug);
    END IF;
    RETURN NEW;
END;


-- Function: create_direct_message

DECLARE
  new_conv_id UUID;
  existing_conv_id UUID;
  recent_dm_count INTEGER;
BEGIN
  -- 1. Prevent concurrent creation of duplicate DMs
  SELECT c.id INTO existing_conv_id
  FROM public.conversations c
  JOIN public.conversation_members cm1 ON c.id = cm1.conversation_id
  JOIN public.conversation_members cm2 ON c.id = cm2.conversation_id
  WHERE c.type = 'dm' 
    AND cm1.user_id = auth.uid() 
    AND cm2.user_id = target_user_id
  LIMIT 1;

  IF existing_conv_id IS NOT NULL THEN
      RETURN existing_conv_id;
  END IF;

  -- 2. Anti-Spam: Limit new DMs to 15 per 24 hours to prevent DB blooming
  SELECT COUNT(*) INTO recent_dm_count
  FROM public.conversations c
  JOIN public.conversation_members cm ON c.id = cm.conversation_id
  WHERE c.type = 'dm' 
    AND cm.user_id = auth.uid()
    AND c.created_at > (now() - interval '24 hours');

  IF recent_dm_count >= 15 THEN
      RAISE EXCEPTION 'Anti-Spam limits engaged: You have reached the maximum number of new direct message threads (15) allowed per 24 hours.';
  END IF;

  -- 3. Create DM
  INSERT INTO public.conversations (type) VALUES ('dm') RETURNING id INTO new_conv_id;
  
  INSERT INTO public.conversation_members (conversation_id, user_id) 
  VALUES (new_conv_id, auth.uid()), (new_conv_id, target_user_id);
  
  RETURN new_conv_id;
END;


-- Function: enforce_squad_message_rules

DECLARE
    v_role text;
    v_muted_until timestamp with time zone;
    v_members_can_post boolean;
    v_members_can_poll boolean;
    v_type text;
BEGIN
    SELECT type, 
           COALESCE((metadata->>'members_can_post')::boolean, true),
           COALESCE((metadata->>'members_can_poll')::boolean, true)
    INTO v_type, v_members_can_post, v_members_can_poll 
    FROM public.conversations 
    WHERE id = NEW.conversation_id;

    IF v_type = 'group' THEN
        SELECT role, muted_until INTO v_role, v_muted_until
        FROM public.conversation_members
        WHERE conversation_id = NEW.conversation_id AND user_id = NEW.sender_id;

        IF v_role IS NULL THEN RAISE EXCEPTION 'Access Denied: You are not a member of this squad.'; END IF;
        IF v_muted_until IS NOT NULL AND v_muted_until > now() THEN RAISE EXCEPTION 'Access Denied: You are currently restricted from posting.'; END IF;
        IF v_members_can_post = false AND v_role NOT IN ('owner', 'admin') THEN RAISE EXCEPTION 'Access Denied: Administrators have temporarily disabled posting.'; END IF;

        -- Intercept Poll Attachments and check permissions
        IF NEW.attachments IS NOT NULL AND jsonb_typeof(NEW.attachments) = 'array' THEN
            IF EXISTS (SELECT 1 FROM jsonb_array_elements(NEW.attachments) AS elem WHERE elem->>'type' = 'poll') THEN
                IF v_members_can_poll = false AND v_role NOT IN ('owner', 'admin') THEN
                    RAISE EXCEPTION 'Access Denied: Administrators have disabled polling for members.';
                END IF;
            END IF;
        END IF;
    END IF;

    RETURN NEW;
END;


-- Function: leave_squad

DECLARE
    v_role text;
BEGIN
    SELECT role INTO v_role FROM public.conversation_members
    WHERE conversation_id = req_conv_id AND user_id = auth.uid();
    
    IF v_role = 'owner' THEN
        RAISE EXCEPTION 'Owners cannot leave their own group. You must delete the group instead.';
    END IF;

    DELETE FROM public.conversation_members
    WHERE conversation_id = req_conv_id AND user_id = auth.uid();
END;


-- Function: enforce_forward_privacy

DECLARE
   origin_privacy text;
   origin_type text;
   real_sender_name text;
   real_sender_avatar text;
BEGIN
   IF NEW.forward_meta IS NOT NULL AND NEW.forward_meta->>'original_conversation_id' IS NOT NULL THEN
      -- Fetch the truth about the origin conversation
      SELECT type, metadata->>'privacy' INTO origin_type, origin_privacy
      FROM public.conversations 
      WHERE id = (NEW.forward_meta->>'original_conversation_id')::uuid;

      -- BLOCK 1: Absolute blockade against extracting from private groups
      IF origin_type = 'group' AND origin_privacy = 'private' THEN
         RAISE EXCEPTION 'Access Denied: Cannot forward messages originating from a private group.';
      END IF;

      -- BLOCK 2: Anti-Spoofing. Force overwrite the sender's identity with DB truth.
      IF NEW.forward_meta->>'original_sender_id' IS NOT NULL THEN
          SELECT full_name, avatar_url INTO real_sender_name, real_sender_avatar
          FROM public.profiles
          WHERE id = (NEW.forward_meta->>'original_sender_id')::uuid;

          IF FOUND THEN
              -- Overwrite whatever the client sent with the absolute truth
              NEW.forward_meta := jsonb_set(NEW.forward_meta, '{original_sender_name}', to_jsonb(real_sender_name), true);
              NEW.forward_meta := jsonb_set(NEW.forward_meta, '{original_sender_avatar}', to_jsonb(COALESCE(real_sender_avatar, '')), true);
          ELSE
              -- If sender ID doesn't exist, flag it
              NEW.forward_meta := jsonb_set(NEW.forward_meta, '{original_sender_name}', '"Deleted Account"', true);
          END IF;
      END IF;
   END IF;
   RETURN NEW;
END;


-- Function: squad_ban_member

DECLARE
    executor_role text;
    target_role text;
BEGIN
    SELECT role INTO executor_role FROM public.conversation_members WHERE conversation_id = req_conv_id AND user_id = auth.uid();
    IF executor_role NOT IN ('owner', 'admin') THEN RAISE EXCEPTION 'Access Denied: Administrative privileges required.'; END IF;

    SELECT role INTO target_role FROM public.conversation_members WHERE conversation_id = req_conv_id AND user_id = req_target_id;
    
    IF target_role = 'owner' THEN RAISE EXCEPTION 'Mutiny Prevented: You cannot ban the group owner.'; END IF;
    IF target_role = 'admin' AND executor_role != 'owner' THEN RAISE EXCEPTION 'Hierarchy Violation: Only the owner can ban an admin.'; END IF;

    INSERT INTO public.squad_bans (conversation_id, user_id, banned_until) 
    VALUES (req_conv_id, req_target_id, req_banned_until)
    ON CONFLICT (conversation_id, user_id) 
    DO UPDATE SET banned_until = EXCLUDED.banned_until;
    
    DELETE FROM public.conversation_members WHERE conversation_id = req_conv_id AND user_id = req_target_id;
END;


-- Function: squad_mute_member

DECLARE
    executor_role text;
    target_role text;
BEGIN
    SELECT role INTO executor_role FROM public.conversation_members WHERE conversation_id = req_conv_id AND user_id = auth.uid();
    IF executor_role NOT IN ('owner', 'admin') THEN RAISE EXCEPTION 'Access Denied: Administrative privileges required.'; END IF;

    SELECT role INTO target_role FROM public.conversation_members WHERE conversation_id = req_conv_id AND user_id = req_target_id;
    
    IF target_role = 'owner' THEN RAISE EXCEPTION 'Mutiny Prevented: You cannot mute the group owner.'; END IF;
    IF target_role = 'admin' AND executor_role != 'owner' THEN RAISE EXCEPTION 'Hierarchy Violation: Only the owner can mute an admin.'; END IF;

    UPDATE public.conversation_members SET muted_until = req_muted_until 
    WHERE conversation_id = req_conv_id AND user_id = req_target_id;
END;


-- Function: rate_limit_messages_fn

DECLARE
    recent_count INTEGER;
BEGIN
    IF auth.role() = 'authenticated' THEN
        -- Count how many messages this user sent in the last 60 seconds
        SELECT COUNT(*) INTO recent_count
        FROM public.messages
        WHERE sender_id = NEW.sender_id
        AND created_at > (now() - interval '1 minute');

        -- Cap at 60 messages per minute (1 per second on average is plenty)
        IF recent_count >= 60 THEN
            RAISE EXCEPTION 'Rate Limit Exceeded: You are sending messages too quickly. Please wait a minute.';
        END IF;
    END IF;
    RETURN NEW;
END;


-- Function: enforce_attachment_limits

BEGIN
    -- Check if attachments exist and if the array length exceeds 10
    IF NEW.attachments IS NOT NULL AND jsonb_array_length(NEW.attachments) > 10 THEN
        RAISE EXCEPTION 'Payload Rejected: Maximum of 10 attachments allowed per message.';
    END IF;
    RETURN NEW;
END;


-- Function: get_featured_events

BEGIN
    RETURN QUERY 
    SELECT fe.id, fe.title, fe.body, fe.image_url, fe.tag_text, fe.tag_color, 
           fe.button_text, fe.button_color, fe.action_type, fe.html_content, 
           fe.external_url, fe.app_route, fe.metadata, fe.created_at
    FROM public.featured_events fe
    WHERE fe.is_active = true
    ORDER BY fe.created_at DESC;
END;


-- Function: reply_to_peer_question

DECLARE
    v_asker_id uuid;
    v_q_title text;
    v_dm_id uuid;
    v_replier_name text;
    v_msg_id uuid;
BEGIN
    -- Locate target
    SELECT user_id, title INTO v_asker_id, v_q_title FROM public.peer_questions WHERE id = req_question_id;
    IF NOT FOUND THEN RAISE EXCEPTION 'Question not found'; END IF;
    IF v_asker_id = auth.uid() THEN RAISE EXCEPTION 'Cannot reply to your own question.'; END IF;

    -- Get sender identity
    SELECT full_name INTO v_replier_name FROM public.profiles WHERE id = auth.uid();

    -- Instanciate or grab existing DM via our previous robust RPC
    v_dm_id := public.create_direct_message(v_asker_id);

    -- Insert the quoted message context and the reply, returning the specific message ID
    INSERT INTO public.messages (conversation_id, sender_id, text)
    VALUES (v_dm_id, auth.uid(), 'Replying to your question: "' || v_q_title || '"' || E'\n\n' || req_reply_text)
    RETURNING id INTO v_msg_id;

    -- [NEW]: Increment the real reply counter
    UPDATE public.peer_questions
    SET replies_count = replies_count + 1
    WHERE id = req_question_id;

    -- Fire the refined notification with exact deep-link payload
    INSERT INTO public.notifications (user_id, type, title, description, icon, action_data)
    VALUES (
        v_asker_id, 
        'study', 
        v_replier_name || ' answered your question!', 
        'They replied to your question regarding "' || v_q_title || '".',
        'fa-comment-dots',
        jsonb_build_object('action', 'open_chat', 'conversation_id', v_dm_id, 'message_id', v_msg_id, 'chat_type', 'dm')
    );
END;


-- Function: get_and_rotate_gemini_key

DECLARE
    target_id bigint;
    found_key text;
END_TIME timestamptz;
BEGIN
    -- Select the least-recently-used active 'gemini' key that is not on cooldown
    SELECT id, api_key
    INTO target_id, found_key
    FROM public.api_keys
    WHERE service = 'gemini'
      AND is_active = true
      AND (cooldown_until IS NULL OR cooldown_until <= now())
    ORDER BY last_used_at ASC NULLS FIRST
    LIMIT 1
    FOR UPDATE SKIP LOCKED; -- High-concurrency safety lock

    -- If we successfully found a key, update its last_used_at and return it
    IF found_key IS NOT NULL THEN
        UPDATE public.api_keys
        SET last_used_at = now()
        WHERE id = target_id;
        
        selected_key := found_key;
        RETURN NEXT;
    END IF;
END;


-- Function: cooldown_gemini_key

BEGIN
    -- Put the specific gemini key on cooldown for 5 minutes
    UPDATE public.api_keys
    SET cooldown_until = now() + INTERVAL '5 minutes'
    WHERE api_key = expired_key
      AND service = 'gemini';
END;


-- Function: force_live_question_defaults_fn

BEGIN
    IF auth.role() = 'authenticated' THEN
        -- Strip any malicious auto-approval or pin attempts
        NEW.status := 'pending';
        NEW.is_pinned := false;
        
        -- Strictly force the real sender identity (No identity spoofing!)
        NEW.sender_id := auth.uid();
    END IF;
    RETURN NEW;
END;


-- Function: prevent_msg_tampering_fn

BEGIN
    IF auth.role() = 'authenticated' THEN
        -- Prevent teleportation & impersonation
        IF NEW.conversation_id != OLD.conversation_id THEN RAISE EXCEPTION 'Security Violation: Cannot move messages.'; END IF;
        IF NEW.sender_id != OLD.sender_id THEN RAISE EXCEPTION 'Security Violation: Cannot change sender.'; END IF;
        IF NEW.forward_meta IS DISTINCT FROM OLD.forward_meta THEN RAISE EXCEPTION 'Security Violation: Cannot tamper with forward metadata.'; END IF;
        IF NEW.attachments IS DISTINCT FROM OLD.attachments THEN RAISE EXCEPTION 'Security Violation: Cannot alter message attachments.'; END IF;
        IF NEW.reply_to_id IS DISTINCT FROM OLD.reply_to_id THEN RAISE EXCEPTION 'Security Violation: Cannot alter reply target.'; END IF;
        
        -- Prevent editing messages older than 24 hours & FORCE the is_edited flag
        IF NEW.text IS DISTINCT FROM OLD.text THEN
            IF OLD.created_at < (now() - interval '24 hours') THEN
                RAISE EXCEPTION 'Time Limit Exceeded: Messages cannot be edited after 24 hours.';
            END IF;
            NEW.is_edited := true; -- OVERWRITE CLIENT PAYLOAD
        END IF;
    END IF;
    RETURN NEW;
END;


-- Function: cast_poll_vote

DECLARE
    v_msg record;
    v_poll jsonb;
    v_deadline timestamptz;
    v_allow_revote boolean;
    v_allow_multiple boolean;
    v_has_voted boolean;
BEGIN
    SELECT * INTO v_msg FROM public.messages WHERE id = req_message_id;
    IF NOT FOUND THEN RAISE EXCEPTION 'Message not found'; END IF;
    
    -- Extract the poll attachment
    SELECT elem INTO v_poll FROM jsonb_array_elements(v_msg.attachments) AS elem WHERE elem->>'type' = 'poll' LIMIT 1;
    IF v_poll IS NULL THEN RAISE EXCEPTION 'No poll found in this message'; END IF;
    
    -- Load physics settings
    v_deadline := (v_poll->'poll_data'->>'deadline')::timestamptz;
    v_allow_revote := COALESCE((v_poll->'poll_data'->>'allow_revote')::boolean, false);
    v_allow_multiple := COALESCE((v_poll->'poll_data'->>'multiple_answers')::boolean, false);
    
    -- Check temporal boundaries
    IF v_deadline IS NOT NULL AND v_deadline < now() THEN
        RAISE EXCEPTION 'Poll has ended';
    END IF;
    
    -- If single-choice, purge other selections
    IF NOT v_allow_multiple THEN
        DELETE FROM public.poll_votes 
        WHERE message_id = req_message_id AND user_id = auth.uid() AND option_index != req_option_index;
    END IF;
    
    -- Check specific vote existence for toggling
    SELECT EXISTS(SELECT 1 FROM public.poll_votes WHERE message_id = req_message_id AND user_id = auth.uid() AND option_index = req_option_index) INTO v_has_voted;
    
    IF v_has_voted THEN
        IF v_allow_revote THEN
            DELETE FROM public.poll_votes WHERE message_id = req_message_id AND user_id = auth.uid() AND option_index = req_option_index;
        ELSE
            RAISE EXCEPTION 'Revoting is disabled for this poll';
        END IF;
    ELSE
        INSERT INTO public.poll_votes (message_id, user_id, option_index) VALUES (req_message_id, auth.uid(), req_option_index);
    END IF;
END;


-- Function: get_user_conversations

BEGIN
  RETURN QUERY
  SELECT 
    c.id as conversation_id,
    c.type::text,
    c.title,
    c.avatar_url,
    c.last_message_at,
    (
        SELECT CASE 
                 WHEN COALESCE(m.text, '') != '' THEN m.text 
                 WHEN m.attachments IS NOT NULL AND jsonb_typeof(m.attachments) = 'array' AND jsonb_array_length(m.attachments) > 0 AND m.attachments->0->>'type' = 'poll' THEN '📊 Poll' 
                 ELSE '' 
               END 
        FROM public.messages m 
        WHERE m.conversation_id = c.id 
        ORDER BY m.created_at DESC 
        LIMIT 1
    ) as last_message_text,
    (
        SELECT count(*) 
        FROM public.messages m2 
        WHERE m2.conversation_id = c.id 
          AND m2.sender_id != req_user_id 
          AND m2.created_at > cm.last_read_at
    ) as unread_count,
    (
        SELECT p.full_name 
        FROM public.conversation_members cm2 
        JOIN public.profiles p ON p.id = cm2.user_id 
        WHERE cm2.conversation_id = c.id AND cm2.user_id != req_user_id 
        LIMIT 1
    ) as other_user_name,
    (
        SELECT p.avatar_url 
        FROM public.conversation_members cm2 
        JOIN public.profiles p ON p.id = cm2.user_id 
        WHERE cm2.conversation_id = c.id AND cm2.user_id != req_user_id 
        LIMIT 1
    ) as other_user_avatar,
    (
        SELECT p.id 
        FROM public.conversation_members cm2 
        JOIN public.profiles p ON p.id = cm2.user_id 
        WHERE cm2.conversation_id = c.id AND cm2.user_id != req_user_id 
        LIMIT 1
    ) as other_user_id,
    (
        SELECT p.last_seen_at 
        FROM public.conversation_members cm2 
        JOIN public.profiles p ON p.id = cm2.user_id 
        WHERE cm2.conversation_id = c.id AND cm2.user_id != req_user_id 
        LIMIT 1
    ) as other_user_last_seen,
    COALESCE(c.metadata, '{}'::jsonb) as metadata
  FROM public.conversations c
  JOIN public.conversation_members cm ON c.id = cm.conversation_id
  WHERE cm.user_id = req_user_id
  ORDER BY c.last_message_at DESC;
END;


-- Function: check_phone_registered

BEGIN
    RETURN EXISTS (SELECT 1 FROM public.profiles WHERE phone = req_phone);
END;


-- Function: check_phone_link_status

DECLARE
    v_user_id uuid;
    v_email text;
    v_is_transient boolean := false;
    v_local_part text;
    v_domain_part text;
    v_masked_email text;
    v_len int;
BEGIN
    -- 1. Find the profile holding this phone number
    SELECT id INTO v_user_id 
    FROM public.profiles 
    WHERE phone = req_phone 
    LIMIT 1;

    -- If no profile has this phone, it is available
    IF v_user_id IS NULL THEN
        RETURN jsonb_build_object('exists', false);
    END IF;

    -- 2. Fetch the associated auth email
    SELECT email INTO v_email 
    FROM auth.users 
    WHERE id = v_user_id 
    LIMIT 1;

    IF v_email IS NULL THEN
        RETURN jsonb_build_object('exists', false);
    END IF;

    -- 3. Determine if the account is a transient Telegram-only placeholder
    IF v_email LIKE '%@linkup.invalid' THEN
        v_is_transient := true;
    END IF;

    -- 4. Apply dynamic length-aware masking
    v_local_part := split_part(v_email, '@', 1);
    v_domain_part := split_part(v_email, '@', 2);
    v_len := length(v_local_part);

    IF v_len <= 1 THEN
        v_masked_email := '*@' || v_domain_part;
    ELSIF v_len = 2 THEN
        v_masked_email := left(v_local_part, 1) || '*@' || v_domain_part;
    ELSIF v_len <= 4 THEN
        v_masked_email := left(v_local_part, 1) || repeat('*', v_len - 2) || right(v_local_part, 1) || '@' || v_domain_part;
    ELSE
        -- 5 or more characters: Show first 2, hide middle, show last 2
        v_masked_email := left(v_local_part, 2) || repeat('*', v_len - 4) || right(v_local_part, 2) || '@' || v_domain_part;
    END IF;

    RETURN jsonb_build_object(
        'exists', true,
        'is_transient', v_is_transient,
        'masked_email', v_masked_email
    );
END;


-- Function: acquire_question_answers_jobs

BEGIN
    RETURN QUERY
    WITH locked AS (
        SELECT question_id
        FROM public.question_processing_progress
        WHERE book_id = p_book_id AND status = 'pending'
        LIMIT p_limit
        FOR UPDATE SKIP LOCKED
    )
    UPDATE public.question_processing_progress qpp
    SET status = 'processing', processed_at = now()
    FROM locked
    WHERE qpp.question_id = locked.question_id
    RETURNING qpp.question_id;
END;


-- Function: get_my_referrals

DECLARE
    result jsonb;
BEGIN
    SELECT jsonb_agg(jsonb_build_object(
        'id', r.id,
        'status', r.status,
        'created_at', r.created_at,
        'referee_name', p.full_name,
        'referee_username', p.username,
        'referee_avatar', p.avatar_url
    ) ORDER BY r.created_at DESC) INTO result
    FROM public.referrals r
    JOIN public.profiles p ON p.id = r.referee_id
    WHERE r.referrer_id = auth.uid();
    
    RETURN COALESCE(result, '[]'::jsonb);
END;


-- Function: sync_linkoin_balance

BEGIN
    UPDATE public.profiles
    SET linkoin_balance = COALESCE(linkoin_balance, 0) + NEW.amount
    WHERE id = NEW.user_id;
    RETURN NEW;
END;


-- Function: claim_telegram_verification_reward

DECLARE
    v_user record;
    v_key text;
BEGIN
    -- 1. Fetch user status and lock the row to prevent concurrent race conditions
    SELECT id, registered_with_telegram INTO v_user
    FROM public.profiles
    WHERE id = auth.uid()
    FOR UPDATE;

    IF v_user IS NULL THEN
        RAISE EXCEPTION 'User not found';
    END IF;

    IF v_user.registered_with_telegram IS NOT TRUE THEN
        RAISE EXCEPTION 'You must verify your Telegram account first.';
    END IF;

    -- 2. Check Idempotency (Has this specific reward already been claimed?)
    v_key := 'tg_verify_reward_' || v_user.id::text;

    IF EXISTS (SELECT 1 FROM public.linkoin_transactions WHERE idempotency_key = v_key) THEN
        RAISE EXCEPTION 'Reward already claimed.';
    END IF;

    -- 3. Insert transaction (Trigger automatically updates balance)
    INSERT INTO public.linkoin_transactions (user_id, amount, transaction_type, description, idempotency_key)
    VALUES (v_user.id, 50, 'reward', 'Telegram Verification Mission', v_key);

    RETURN jsonb_build_object('success', true, 'amount_granted', 50);
END;


-- Function: register_referral

DECLARE
    v_referrer_id UUID;
BEGIN
    -- Resolve the username to an ID
    SELECT id INTO v_referrer_id FROM public.profiles WHERE username = referrer_username LIMIT 1;
    
    IF v_referrer_id IS NOT NULL AND v_referrer_id != auth.uid() THEN
        -- Safely insert the pending referral (ignores if referee already has an inviter)
        INSERT INTO public.referrals (referrer_id, referee_id, status)
        VALUES (v_referrer_id, auth.uid(), 'pending')
        ON CONFLICT (referee_id) DO NOTHING;
    END IF;
END;


-- Function: update_user_streak

DECLARE
    v_today date;
    v_yesterday date;
    v_last_update date;
BEGIN
    v_today := (now() AT TIME ZONE 'Africa/Addis_Ababa')::date;
    v_yesterday := v_today - interval '1 day';
    
    -- Lock row for safety
    SELECT last_streak_update INTO v_last_update
    FROM public.profiles WHERE id = auth.uid() FOR UPDATE;
    
    IF v_last_update IS NULL OR v_last_update < v_yesterday THEN
        -- Streak broken or first ever load
        UPDATE public.profiles 
        SET current_streak = 1, last_streak_update = v_today
        WHERE id = auth.uid();
    ELSIF v_last_update = v_yesterday THEN
        -- Active yesterday, increment!
        UPDATE public.profiles 
        SET current_streak = current_streak + 1,
            longest_streak = GREATEST(longest_streak, current_streak + 1),
            last_streak_update = v_today
        WHERE id = auth.uid();
    END IF;
    -- If v_last_update = v_today, they already checked in. Do nothing.
END;


-- Function: trigger_referral_reward

DECLARE
    v_referral record;
    v_referrer_key text;
BEGIN
    -- Only trigger when registered_with_telegram transitions from false to true
    IF NEW.registered_with_telegram = true AND OLD.registered_with_telegram = false THEN
        
        -- Check if this user was invited by someone
        SELECT * INTO v_referral FROM public.referrals WHERE referee_id = NEW.id AND status = 'pending' LIMIT 1;
        
        IF v_referral IS NOT NULL THEN
            -- 1. Mark as completed
            UPDATE public.referrals SET status = 'completed' WHERE id = v_referral.id;
            
            v_referrer_key := 'ref_bonus_referrer_' || v_referral.referee_id::text;
            
            -- 2. Reward the Referrer (+30). The referee relies on the 100 default coins given on signup.
            INSERT INTO public.linkoin_transactions (user_id, amount, transaction_type, description, idempotency_key)
            VALUES (v_referral.referrer_id, 30, 'reward', 'Squad Network Invite Bonus', v_referrer_key)
            ON CONFLICT (idempotency_key) DO NOTHING;
        END IF;
    END IF;
    RETURN NEW;
END;


-- Function: get_current_streak_mission

DECLARE
    v_semester int;
    v_current int;
    v_target int;
    v_reward int;
    v_claimed boolean;
BEGIN
    SELECT COALESCE((value->>'semester')::int, 1) INTO v_semester FROM public.system_config WHERE key = 'academic_calendar';
    SELECT current_streak INTO v_current FROM public.profiles WHERE id = auth.uid();
    
    -- Progressively scan targets. Stops and returns the FIRST unclaimed one.
    FOREACH v_target IN ARRAY ARRAY[7, 15, 30, 60, 90, 120, 150, 180, 210, 240, 270, 300, 330, 360]
    LOOP
        SELECT EXISTS(
            SELECT 1 FROM public.linkoin_transactions 
            WHERE idempotency_key = 'streak_claim_' || v_target::text || '_' || auth.uid()::text || '_sem' || v_semester::text
        ) INTO v_claimed;
        
        IF NOT v_claimed THEN
            IF v_target = 7 THEN v_reward := 70;
            ELSIF v_target = 15 THEN v_reward := 150;
            ELSIF v_target = 30 THEN v_reward := 300;
            ELSIF v_target = 60 THEN v_reward := 400;
            ELSE v_reward := 500 + (((v_target - 90) / 30) * 100);
            END IF;
            
            RETURN jsonb_build_object(
                'target', v_target, 
                'reward', v_reward, 
                'status', CASE WHEN v_current >= v_target THEN 'claimable' ELSE 'in_progress' END, 
                'current', v_current
            );
        END IF;
    END LOOP;
    RETURN jsonb_build_object('status', 'maxed_out');
END;


-- Function: claim_streak_milestone

DECLARE
    v_semester int;
    v_current int;
    v_reward int;
    v_key text;
BEGIN
    SELECT COALESCE((value->>'semester')::int, 1) INTO v_semester FROM public.system_config WHERE key = 'academic_calendar';
    SELECT current_streak INTO v_current FROM public.profiles WHERE id = auth.uid() FOR UPDATE;
    
    IF v_current < p_target THEN RAISE EXCEPTION 'Streak target not reached yet.'; END IF;
    
    v_key := 'streak_claim_' || p_target::text || '_' || auth.uid()::text || '_sem' || v_semester::text;
    IF EXISTS (SELECT 1 FROM public.linkoin_transactions WHERE idempotency_key = v_key) THEN
        RAISE EXCEPTION 'Milestone already claimed.';
    END IF;
    
    IF p_target = 7 THEN v_reward := 70;
    ELSIF p_target = 15 THEN v_reward := 150;
    ELSIF p_target = 30 THEN v_reward := 300;
    ELSIF p_target = 60 THEN v_reward := 400;
    ELSE v_reward := 500 + (((p_target - 90) / 30) * 100);
    END IF;
    
    INSERT INTO public.linkoin_transactions (user_id, amount, transaction_type, description, idempotency_key)
    VALUES (auth.uid(), v_reward, 'reward', p_target::text || ' Day Streak Bonus', v_key);
    
    RETURN jsonb_build_object('success', true);
END;


-- Function: admin_reset_semester_streaks

DECLARE
    v_old_semester int;
BEGIN
    SELECT COALESCE((value->>'semester')::int, 1) INTO v_old_semester FROM public.system_config WHERE key = 'academic_calendar';
    
    UPDATE public.system_config SET value = jsonb_build_object('semester', v_old_semester + 1) WHERE key = 'academic_calendar';
    UPDATE public.profiles SET current_streak = 0, last_streak_update = NULL;
END;


-- Function: check_squad_slug_available

BEGIN
  RETURN NOT EXISTS (SELECT 1 FROM public.conversations WHERE metadata->>'slug' = req_slug);
END;


-- Function: create_study_group

DECLARE
  base_slug text;
  candidate_slug text;
  counter integer := 1;
  new_conv_id uuid;
  final_metadata jsonb;
  owned_count integer;
BEGIN
  SELECT count(*) INTO owned_count 
  FROM public.conversations 
  WHERE owner_id = auth.uid() AND type = 'group';
  
  IF owned_count >= 3 THEN
      RAISE EXCEPTION 'Limit reached. You can only own up to 3 study groups/classes.';
  END IF;

  final_metadata := COALESCE(req_metadata, '{}'::jsonb);

  IF (final_metadata->>'privacy' IS NULL OR final_metadata->>'privacy' = 'public') THEN
      IF final_metadata ? 'slug' AND final_metadata->>'slug' != '' THEN
          base_slug := final_metadata->>'slug';
      ELSE
          base_slug := regexp_replace(lower(req_title), '[^a-z0-9]', '', 'g');
          IF base_slug = '' THEN base_slug := 'squad'; END IF;
      END IF;
      candidate_slug := base_slug;
      LOOP
        WHILE EXISTS (SELECT 1 FROM public.conversations WHERE metadata->>'slug' = candidate_slug) LOOP
          candidate_slug := base_slug || counter::text;
          counter := counter + 1;
        END LOOP;
        BEGIN
          final_metadata := final_metadata || jsonb_build_object('slug', candidate_slug);
          INSERT INTO public.conversations (type, title, metadata, owner_id)
          VALUES ('group', req_title, final_metadata, auth.uid())
          RETURNING id INTO new_conv_id;
          EXIT; 
        EXCEPTION WHEN unique_violation THEN
          candidate_slug := base_slug || counter::text;
          counter := counter + 1;
        END;
      END LOOP;
  ELSE
      INSERT INTO public.conversations (type, title, metadata, owner_id)
      VALUES ('group', req_title, final_metadata, auth.uid())
      RETURNING id INTO new_conv_id;
  END IF;

  INSERT INTO public.conversation_members (conversation_id, user_id, role)
  VALUES (new_conv_id, auth.uid(), 'owner');

  RETURN new_conv_id;
END;


-- Function: check_profile_class_membership

BEGIN
    IF NEW.class_id IS NOT NULL THEN
        IF NOT EXISTS (
            SELECT 1 FROM public.conversation_members 
            WHERE conversation_id = NEW.class_id AND user_id = NEW.id
        ) THEN
            RAISE EXCEPTION 'You must join the class group before linking it to your profile.';
        END IF;
    END IF;
    RETURN NEW;
END;


-- Function: handle_member_leave_or_kick

BEGIN
    UPDATE public.profiles
    SET class_id = NULL
    WHERE id = OLD.user_id AND class_id = OLD.conversation_id;
    RETURN OLD;
END;


-- Function: get_social_discovery

DECLARE
    my_uni UUID;
    my_dept TEXT;
BEGIN
    SELECT p.university_id, p.department INTO my_uni, my_dept
    FROM public.profiles p WHERE p.id = req_user_id;

    RETURN QUERY
    SELECT 
        p.id, p.full_name, p.username, p.avatar_url, p.university_id, p.department,
        CASE 
            WHEN p.university_id = my_uni AND p.department = my_dept THEN 1
            WHEN p.university_id = my_uni THEN 2
            ELSE 3
        END as tier
    FROM public.profiles p
    WHERE p.id != req_user_id
    AND p.id NOT IN (
        SELECT cm2.user_id
        FROM public.conversation_members cm1
        JOIN public.conversation_members cm2 ON cm1.conversation_id = cm2.conversation_id
        JOIN public.conversations c ON cm1.conversation_id = c.id
        WHERE cm1.user_id = req_user_id AND cm2.user_id != req_user_id AND c.type = 'dm'
    )
    ORDER BY tier ASC, p.last_seen_at DESC NULLS LAST
    LIMIT 30;
END;


-- Function: join_study_group

DECLARE
    ban_record RECORD;
    conv_privacy text;
    db_token text;
BEGIN
    IF req_user_id != auth.uid() THEN
        RAISE EXCEPTION 'Access Denied: You cannot force another user to join a group.';
    END IF;

    IF EXISTS (
        SELECT 1 FROM public.conversation_members 
        WHERE conversation_id = req_conversation_id AND user_id = req_user_id
    ) THEN
        RETURN;
    END IF;

    -- Privacy verification block
    SELECT metadata->>'privacy', metadata->>'private_invite_token' INTO conv_privacy, db_token 
    FROM public.conversations WHERE id = req_conversation_id;
    
    IF conv_privacy = 'private' THEN
        IF req_token IS NULL OR req_token != db_token THEN
            RAISE EXCEPTION 'Access Denied: This group is private or the invite link is invalid.';
        END IF;
    END IF;

    SELECT banned_until INTO ban_record FROM public.squad_bans WHERE conversation_id = req_conversation_id AND user_id = req_user_id;
    IF FOUND THEN
        IF ban_record.banned_until IS NULL OR ban_record.banned_until > now() THEN
            RAISE EXCEPTION 'Access Denied: You are banned from this group.';
        ELSE
            DELETE FROM public.squad_bans WHERE conversation_id = req_conversation_id AND user_id = req_user_id;
        END IF;
    END IF;

    INSERT INTO public.conversation_members (conversation_id, user_id, role)
    VALUES (req_conversation_id, req_user_id, 'member')
    ON CONFLICT DO NOTHING;
END;


-- Function: create_private_invite_link

DECLARE
    v_role text;
    new_token text;
    current_meta jsonb;
BEGIN
    SELECT role INTO v_role FROM public.conversation_members WHERE conversation_id = req_conv_id AND user_id = auth.uid();
    IF v_role != 'owner' THEN
        RAISE EXCEPTION 'Access Denied: Only the owner can generate an invite link.';
    END IF;

    new_token := substring(md5(random()::text), 1, 16);
    
    SELECT metadata INTO current_meta FROM public.conversations WHERE id = req_conv_id;
    current_meta := jsonb_set(COALESCE(current_meta, '{}'::jsonb), '{private_invite_token}', to_jsonb(new_token));

    UPDATE public.conversations SET metadata = current_meta WHERE id = req_conv_id;
    RETURN new_token;
END;


-- Function: revoke_private_invite_link

DECLARE
    v_role text;
    current_meta jsonb;
BEGIN
    SELECT role INTO v_role FROM public.conversation_members WHERE conversation_id = req_conv_id AND user_id = auth.uid();
    IF v_role != 'owner' THEN
        RAISE EXCEPTION 'Access Denied: Only the owner can revoke an invite link.';
    END IF;
    
    SELECT metadata INTO current_meta FROM public.conversations WHERE id = req_conv_id;
    current_meta := current_meta - 'private_invite_token';

    UPDATE public.conversations SET metadata = current_meta WHERE id = req_conv_id;
END;


-- Function: get_private_group_by_token

DECLARE
    group_record record;
    member_count int;
    user_is_member boolean;
BEGIN
    SELECT id, title, avatar_url, metadata
    INTO group_record
    FROM public.conversations
    WHERE metadata->>'private_invite_token' = req_token AND type = 'group';

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Invalid or expired invitation link.';
    END IF;

    -- Tally the current roster
    SELECT count(*) INTO member_count FROM public.conversation_members WHERE conversation_id = group_record.id;
    
    -- Evaluate the requester's membership silently
    SELECT EXISTS(SELECT 1 FROM public.conversation_members WHERE conversation_id = group_record.id AND user_id = auth.uid()) INTO user_is_member;

    RETURN jsonb_build_object(
        'id', group_record.id,
        'title', group_record.title,
        'avatar_url', group_record.avatar_url,
        'focus', group_record.metadata->>'focus',
        'member_count', member_count,
        'is_member', user_is_member
    );
END;


-- Function: get_peer_questions

BEGIN
    RETURN QUERY
    SELECT pq.id, pq.title, pq.body, pq.course_tag, pq.created_at,
           pq.user_id AS asker_id, p.full_name AS asker_name, p.avatar_url AS asker_avatar,
           pq.replies_count
    FROM public.peer_questions pq
    JOIN public.profiles p ON p.id = pq.user_id
    ORDER BY pq.created_at DESC
    LIMIT 50;
END;


-- Function: kill_live_session

DECLARE
    v_role text;
    v_host_id text;
BEGIN
    -- 1. Identify who is currently hosting
    SELECT metadata->>'live_host_id' INTO v_host_id 
    FROM public.conversations WHERE id = conv_id;
    
    -- 2. Identify the rank of the person trying to kill the session
    SELECT role INTO v_role 
    FROM public.conversation_members 
    WHERE conversation_id = conv_id AND user_id = auth.uid();

    -- 3. The Law: You can only kill it if you are the active host, OR an Admin/Owner
    IF auth.uid()::text != v_host_id AND (v_role IS NULL OR v_role NOT IN ('owner', 'admin')) THEN
        RAISE EXCEPTION 'Security Violation: You are not authorized to terminate this broadcast.';
    END IF;

    -- 4. Execute the safe cleanup of metadata
    UPDATE public.conversations 
    SET metadata = metadata - 'is_live' - 'live_host_id' - 'live_status' - 'live_heartbeat' - 'live_started_at'
    WHERE id = conv_id;

    -- 5. CRITICAL FIX: Purge the discovery engine record so it disappears from the global 'Explore' feed instantly
    DELETE FROM public.live_study_sessions WHERE conversation_id = conv_id;
END;


-- Function: get_live_study_sessions

DECLARE
    my_uni uuid;
    my_stream text;
BEGIN
    -- Get the viewer's academic profile
    SELECT university_id, freshman_stream INTO my_uni, my_stream
    FROM public.profiles WHERE profiles.id = req_user_id;

    RETURN QUERY
    WITH EligibleSessions AS (
        SELECT s.id, s.conversation_id, s.course_name, s.lesson_topic, s.active_user_ids, s.last_updated_at
        FROM public.live_study_sessions s
        JOIN public.conversations c ON s.conversation_id = c.id
        WHERE 
        -- Exclude Private Groups completely
        (c.metadata->>'privacy' = 'public' OR c.metadata->>'privacy' IS NULL)
        -- Only consider sessions active in the last 2 hours
        AND s.last_updated_at > now() - interval '2 hours'
        AND
        -- Miron's Intelligent Course Routing Filter (Safely handles NULL streams)
        CASE 
            WHEN s.course_name ILIKE ANY(ARRAY['%Biology%', '%Chemistry%', '%Physics%']) THEN COALESCE(my_stream, '') = 'Natural Science'
            WHEN s.course_name ILIKE ANY(ARRAY['%Geography%', '%History%', '%Anthropology%']) THEN COALESCE(my_stream, '') = 'Social Science'
            ELSE TRUE 
        END
    ),
    SessionStats AS (
        SELECT 
            es.id AS sid,
            -- Tally exact relational proximity 
            (SELECT count(*) FROM public.profiles p WHERE p.id = ANY(es.active_user_ids) AND p.id != req_user_id AND p.university_id = my_uni AND p.freshman_stream = my_stream) AS classmates_count,
            (SELECT count(*) FROM public.profiles p WHERE p.id = ANY(es.active_user_ids) AND p.id != req_user_id AND p.university_id = my_uni AND p.freshman_stream != my_stream) AS campus_mates_count,
            (SELECT count(*) FROM public.profiles p WHERE p.id = ANY(es.active_user_ids) AND p.id != req_user_id AND p.university_id != my_uni AND p.freshman_stream = my_stream) AS scholars_count,
            
            -- FIX: Count EVERYONE using native array length so RLS doesn't block the count
            cardinality(es.active_user_ids) AS total_count
        FROM EligibleSessions es
    )
    SELECT 
        es.id,
        es.conversation_id,
        es.course_name,
        es.lesson_topic,
        -- The Dynamic Text Engine
        CASE
            WHEN ss.classmates_count > 0 THEN 
                ss.classmates_count::text || ' classmates from your stream are studying this right now. Join and share notes!'
            WHEN ss.campus_mates_count > 0 THEN 
                ss.campus_mates_count::text || ' students from your campus are studying this right now. Join and share notes!'
            WHEN ss.scholars_count > 0 THEN 
                ss.scholars_count::text || ' freshman scholars from other universities are studying this right now.'
            ELSE 
                ss.total_count::text || ' students are studying this right now. Join the session!'
        END AS dynamic_message,
        ss.total_count::integer AS participant_count,
        es.last_updated_at
    FROM EligibleSessions es
    JOIN SessionStats ss ON es.id = ss.sid
    WHERE ss.total_count > 0
    ORDER BY ss.classmates_count DESC, ss.total_count DESC, es.last_updated_at DESC
    LIMIT 5;
END;


-- Function: global_network_search

BEGIN
    RETURN QUERY
    -- 1. Search Users
    SELECT 
        p.id,
        'user'::TEXT AS type,
        p.full_name AS title,
        p.username AS subtitle,
        p.avatar_url,
        '{}'::JSONB AS metadata,
        EXISTS (
            SELECT 1 FROM conversation_members cm1
            JOIN conversation_members cm2 ON cm1.conversation_id = cm2.conversation_id
            JOIN conversations c ON cm1.conversation_id = c.id
            WHERE c.type = 'dm' AND cm1.user_id = req_user_id AND cm2.user_id = p.id
        ) AS is_member
    FROM public.profiles p
    WHERE p.id != req_user_id
      AND (p.full_name ILIKE ('%' || search_term || '%') OR p.username ILIKE ('%' || search_term || '%'))
    
    UNION ALL
    
    -- 2. Search Groups
    SELECT 
        c.id,
        'group'::TEXT AS type,
        c.title,
        COALESCE(c.metadata->>'focus', 'General') AS subtitle,
        c.avatar_url,
        COALESCE(c.metadata, '{}'::jsonb) AS metadata,
        EXISTS (
            SELECT 1 FROM conversation_members cm WHERE cm.conversation_id = c.id AND cm.user_id = req_user_id
        ) AS is_member
    FROM public.conversations c
    WHERE c.type = 'group'
      AND c.title ILIKE ('%' || search_term || '%')
      AND (
          (c.metadata->>'privacy' = 'public' OR c.metadata->>'privacy' IS NULL)
          OR 
          EXISTS (SELECT 1 FROM conversation_members cm WHERE cm.conversation_id = c.id AND cm.user_id = req_user_id)
      )
    LIMIT 30;
END;


-- Function: get_suggested_squads

BEGIN
    RETURN QUERY
    SELECT 
        c.id AS conversation_id,
        c.title::text AS title,
        COALESCE(c.metadata, '{}'::jsonb) AS metadata,
        (SELECT COUNT(*) FROM public.conversation_members cm WHERE cm.conversation_id = c.id)::integer AS m_count
    FROM public.conversations c
    WHERE c.type::text = 'group'
      AND (c.metadata->>'focus' IS DISTINCT FROM 'Class')
      AND (c.metadata->>'privacy' = 'public' OR c.metadata->>'privacy' IS NULL)
      AND NOT EXISTS (
          SELECT 1 FROM public.conversation_members cm2 
          WHERE cm2.conversation_id = c.id AND cm2.user_id = req_user_id
      )
    ORDER BY m_count DESC, c.created_at DESC
    LIMIT 20;
END;


-- Function: get_campus_classes

DECLARE
    v_uni_id uuid;
    v_dept text;
BEGIN
    SELECT university_id, department INTO v_uni_id, v_dept
    FROM public.profiles WHERE id = req_user_id;

    RETURN QUERY
    SELECT 
        c.id AS conversation_id,
        c.title::text AS title,
        COALESCE(c.metadata, '{}'::jsonb) AS metadata,
        (SELECT COUNT(*) FROM public.conversation_members cm WHERE cm.conversation_id = c.id)::integer AS member_count,
        p.full_name AS owner_name,
        p.avatar_url AS owner_avatar,
        (
            CASE 
                WHEN EXISTS (
                    SELECT 1 FROM conversation_members cm1
                    JOIN conversation_members cm2 ON cm1.conversation_id = cm2.conversation_id
                    JOIN conversations dm ON cm1.conversation_id = dm.id
                    WHERE dm.type = 'dm' AND cm1.user_id = req_user_id AND cm2.user_id = c.owner_id
                ) THEN 10 ELSE 0 
            END
            +
            CASE WHEN p.department = v_dept THEN 5 ELSE 0 END
        )::integer AS relevance_score,
        EXISTS (SELECT 1 FROM public.conversation_members cm WHERE cm.conversation_id = c.id AND cm.user_id = req_user_id) AS is_member
    FROM public.conversations c
    JOIN public.profiles p ON c.owner_id = p.id
    WHERE c.type = 'group'
      AND c.metadata->>'focus' = 'Class'
      AND (c.metadata->>'privacy' = 'public' OR c.metadata->>'privacy' IS NULL)
      AND p.university_id = v_uni_id
    ORDER BY relevance_score DESC, member_count DESC, c.created_at DESC;
END;


