-- NEX MDM v18: PIN técnico desde el panel.
--
-- Antes el PIN del acceso técnico de NEX TV venía compilado en el APK, en texto plano. Ahora:
--  - cada empresa define su PIN en el panel (dueño o admin);
--  - NEX define un PIN global, para equipos sin vincular o empresas sin PIN propio (super admin);
--  - el panel calcula PBKDF2-SHA256 con sal en el navegador y acá solo se guarda el hash, nunca el PIN;
--  - el equipo recibe el hash en cada sync (clave "tech_pin") y verifica el PIN localmente (Agent/TechPin.kt).
--
-- Formato de tech_pin: {"v":1, "salt":"<32 hex>", "iter":<10000..200000>, "hash":"<64 hex>"}.
-- Idempotente: se puede correr más de una vez.

-- Tabla aparte (no una columna de mdm_companies): el panel puede leer mdm_companies entera, y con un PIN de 4 dígitos
-- el hash se rompe al instante. Sin políticas ni grants: solo la leen y escriben las funciones de abajo.
create table if not exists public.mdm_company_tech_pins (
  company_id uuid primary key references public.mdm_companies (id) on delete cascade,
  value jsonb not null,
  updated_at timestamptz not null default now(),
  updated_by uuid references auth.users (id) on delete set null
);
alter table public.mdm_company_tech_pins enable row level security;
revoke all on table public.mdm_company_tech_pins from anon, authenticated;

-- Ajustes globales de NEX (por ahora, solo el PIN técnico global). Sin políticas: solo las funciones de abajo.
create table if not exists public.mdm_settings (
  key text primary key,
  value jsonb not null,
  updated_at timestamptz not null default now(),
  updated_by uuid references auth.users (id) on delete set null
);
alter table public.mdm_settings enable row level security;
revoke all on table public.mdm_settings from anon, authenticated;

create or replace function public._mdm_tech_pin_valid(p jsonb)
returns boolean
language sql
immutable
set search_path = public
as $$
  select jsonb_typeof(p) = 'object'
     and (p ->> 'v') = '1'
     and coalesce(p ->> 'salt', '') ~ '^[0-9a-f]{32}$'
     and coalesce(p ->> 'hash', '') ~ '^[0-9a-f]{64}$'
     and jsonb_typeof(p -> 'iter') = 'number'
     and (p ->> 'iter')::numeric between 10000 and 200000
     and (select count(*) from jsonb_object_keys(p)) = 4;
$$;

-- Solo las cuatro claves conocidas, normalizadas: no se guarda nada extra que venga del navegador.
create or replace function public._mdm_tech_pin_clean(p jsonb)
returns jsonb
language sql
immutable
set search_path = public
as $$
  select jsonb_build_object('v', 1, 'salt', p ->> 'salt', 'iter', (p ->> 'iter')::int, 'hash', p ->> 'hash');
$$;

-- Empresa: dueño o admin (con 2FA reciente, como el resto de lo sensible). p_pin null lo borra (vuelve al global).
create or replace function public.mdm_set_tech_pin(p_company uuid, p_pin jsonb)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
  v_role text := public._mdm_company_role(p_company);
begin
  if v_role is null or v_role not in ('owner', 'admin', 'super') then
    raise exception 'Sin permiso sobre esta empresa' using errcode = '42501';
  end if;
  perform public._mdm_require_recent_mfa();
  if p_pin is not null and not public._mdm_tech_pin_valid(p_pin) then
    raise exception 'PIN inválido' using errcode = '22023';
  end if;
  if p_pin is null then
    delete from public.mdm_company_tech_pins where company_id = p_company;
  else
    insert into public.mdm_company_tech_pins (company_id, value, updated_at, updated_by)
    values (p_company, public._mdm_tech_pin_clean(p_pin), now(), auth.uid())
    on conflict (company_id) do update set value = excluded.value, updated_at = now(), updated_by = auth.uid();
  end if;
  insert into public.mdm_audit_log (actor, company_id, action, target, details)
  values (auth.uid(), p_company, case when p_pin is null then 'tech_pin_cleared' else 'tech_pin_set' end, null, '{}'::jsonb);
end;
$$;

revoke all on function public.mdm_set_tech_pin(uuid, jsonb) from public, anon;
grant execute on function public.mdm_set_tech_pin(uuid, jsonb) to authenticated;

-- Global: solo super admin, con 2FA reciente.
create or replace function public.mdm_set_global_tech_pin(p_pin jsonb)
returns void
language plpgsql
security definer
set search_path = public
as $$
begin
  if not public.mdm_is_super_admin() then
    raise exception 'Solo NEX puede cambiar el PIN global' using errcode = '42501';
  end if;
  perform public._mdm_require_recent_mfa();
  if p_pin is null then
    delete from public.mdm_settings where key = 'tech_pin';
  elsif not public._mdm_tech_pin_valid(p_pin) then
    raise exception 'PIN inválido' using errcode = '22023';
  else
    insert into public.mdm_settings (key, value, updated_at, updated_by)
    values ('tech_pin', public._mdm_tech_pin_clean(p_pin), now(), auth.uid())
    on conflict (key) do update set value = excluded.value, updated_at = now(), updated_by = auth.uid();
  end if;
  insert into public.mdm_audit_log (actor, company_id, action, target, details)
  values (auth.uid(), null, case when p_pin is null then 'global_tech_pin_cleared' else 'global_tech_pin_set' end, null, '{}'::jsonb);
end;
$$;

revoke all on function public.mdm_set_global_tech_pin(jsonb) from public, anon;
grant execute on function public.mdm_set_global_tech_pin(jsonb) to authenticated;

-- Para el panel: si hay PIN propio y si hay global. Nunca devuelve el hash.
create or replace function public.mdm_tech_pin_status(p_company uuid default null)
returns jsonb
language plpgsql
stable
security definer
set search_path = public
as $$
declare
  v_company boolean := null;
begin
  if p_company is not null then
    if public._mdm_company_role(p_company) is null then
      raise exception 'Sin permiso sobre esta empresa' using errcode = '42501';
    end if;
    v_company := exists (select 1 from public.mdm_company_tech_pins where company_id = p_company);
  elsif not public.mdm_is_super_admin() then
    raise exception 'Sin permiso' using errcode = '42501';
  end if;
  return jsonb_build_object(
    'company', v_company,
    'global', exists (select 1 from public.mdm_settings where key = 'tech_pin'));
end;
$$;

revoke all on function public.mdm_tech_pin_status(uuid) from public, anon;
grant execute on function public.mdm_tech_pin_status(uuid) to authenticated;

-- Sync (envoltorio de v17): agrega "tech_pin" antes de calcular el config_hash, así un cambio de PIN llega al
-- equipo aunque nada más cambie. Vinculado: el de la empresa o, si no tiene, el global. Sin vincular: el global.
create or replace function public.mdm_device_sync(p_token text, p_firmware text default null,
                                                  p_status jsonb default null, p_events jsonb default null,
                                                  p_hardware_id text default null)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_known text := case when jsonb_typeof(p_status) = 'object' and (p_status ->> 'config_hash') ~ '^[0-9a-f]{32}$'
                       then p_status ->> 'config_hash' end;
  v_resp jsonb;
  v_hash text;
  v_global jsonb := (select value from public.mdm_settings where key = 'tech_pin');
  v_pin jsonb;
begin
  -- El hash no es parte de la salud del equipo: no se guarda en mdm_devices.health.
  v_resp := public._mdm_device_sync_full(
    p_token, p_firmware,
    case when jsonb_typeof(p_status) = 'object' then p_status - 'config_hash' else p_status end,
    p_events, p_hardware_id);
  if v_resp ->> 'status' is distinct from 'paired' then
    if v_resp ->> 'status' = 'unpaired' and v_global is not null then
      v_resp := v_resp || jsonb_build_object('tech_pin', v_global);
    end if;
    return v_resp;
  end if;
  v_pin := coalesce(
    (select t.value from public.mdm_company_tech_pins t where t.company_id = (v_resp -> 'company' ->> 'id')::uuid),
    v_global);
  v_resp := v_resp || jsonb_build_object('tech_pin', coalesce(v_pin, 'null'::jsonb));
  v_hash := md5((v_resp - 'server_time' - 'server_epoch')::text);
  if v_known = v_hash then
    return jsonb_build_object('status', 'paired', 'unchanged', true, 'config_hash', v_hash,
                              'server_time', v_resp -> 'server_time', 'server_epoch', v_resp -> 'server_epoch');
  end if;
  return v_resp || jsonb_build_object('config_hash', v_hash);
end;
$$;

revoke all on function public.mdm_device_sync(text, text, jsonb, jsonb, text) from public;
grant execute on function public.mdm_device_sync(text, text, jsonb, jsonb, text) to anon, authenticated;
