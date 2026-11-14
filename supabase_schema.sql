-- FeiraJusta — Schema PostgreSQL + RLS para Supabase
-- Execute este script no SQL Editor do seu projeto Supabase.

create extension if not exists pgcrypto;

-- 1. Mercados cadastrados pelo usuário
create table if not exists public.markets (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  name text not null check (char_length(name) between 1 and 80),
  neighborhood text not null default '' check (char_length(neighborhood) <= 80),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create index if not exists idx_markets_user_id on public.markets(user_id);

-- 2. Registros de preços
create table if not exists public.price_records (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  product_name text not null check (char_length(product_name) between 1 and 100),
  normalized_name text not null check (char_length(normalized_name) between 1 and 100),
  brand text not null default '' check (char_length(brand) <= 60),
  category text not null default 'Mercearia' check (char_length(category) <= 40),
  unit_label text not null default '1 un' check (char_length(unit_label) <= 30),
  price_cents bigint not null check (price_cents > 0 and price_cents <= 10000000),
  market_id text not null check (char_length(market_id) between 1 and 80),
  market_name text not null check (char_length(market_name) between 1 and 80),
  is_promo boolean not null default false,
  photo_url text not null default '' check (char_length(photo_url) <= 500),
  recorded_at_millis bigint not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

alter table public.price_records
  add column if not exists photo_url text not null default '';

create index if not exists idx_price_records_user_id on public.price_records(user_id);
create index if not exists idx_price_records_recorded on public.price_records(recorded_at_millis desc);

-- 3. Listas compartilhadas em família
create table if not exists public.shopping_lists (
  id uuid primary key default gen_random_uuid(),
  owner_id uuid not null references auth.users(id) on delete cascade,
  member_uids uuid[] not null default '{}'::uuid[] check (coalesce(array_length(member_uids, 1), 0) between 1 and 15),
  title text not null check (char_length(title) between 1 and 80),
  invite_code text not null unique check (invite_code ~ '^[A-Z0-9-]{4,20}$'),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create index if not exists idx_shopping_lists_members on public.shopping_lists using gin(member_uids);

-- 4. Itens das listas
create table if not exists public.shopping_list_items (
  id uuid primary key default gen_random_uuid(),
  list_id uuid not null references public.shopping_lists(id) on delete cascade,
  product_name text not null check (char_length(product_name) between 1 and 100),
  normalized_name text not null check (char_length(normalized_name) between 1 and 100),
  quantity bigint not null default 1 check (quantity between 1 and 999),
  unit_label text not null default '1 un' check (char_length(unit_label) <= 30),
  checked boolean not null default false,
  added_by_uid uuid not null references auth.users(id) on delete cascade,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create index if not exists idx_shopping_list_items_list_id on public.shopping_list_items(list_id);

-- Habilitar Row Level Security (RLS)
alter table public.markets enable row level security;
alter table public.price_records enable row level security;
alter table public.shopping_lists enable row level security;
alter table public.shopping_list_items enable row level security;

-- Políticas de markets (leitura colaborativa entre usuários autenticados; edição/exclusão apenas do autor)
drop policy if exists "markets_owner_all" on public.markets;
drop policy if exists "markets_authenticated_read" on public.markets;
create policy "markets_authenticated_read" on public.markets
  for select
  to authenticated
  using (true);

drop policy if exists "markets_owner_insert" on public.markets;
create policy "markets_owner_insert" on public.markets
  for insert
  to authenticated
  with check (auth.uid() = user_id);

drop policy if exists "markets_owner_update" on public.markets;
create policy "markets_owner_update" on public.markets
  for update
  to authenticated
  using (auth.uid() = user_id)
  with check (auth.uid() = user_id);

drop policy if exists "markets_owner_delete" on public.markets;
create policy "markets_owner_delete" on public.markets
  for delete
  to authenticated
  using (auth.uid() = user_id);

-- Políticas de price_records (leitura colaborativa entre usuários autenticados; edição/exclusão apenas do autor)
drop policy if exists "price_records_owner_all" on public.price_records;
drop policy if exists "price_records_authenticated_read" on public.price_records;
create policy "price_records_authenticated_read" on public.price_records
  for select
  to authenticated
  using (true);

drop policy if exists "price_records_owner_insert" on public.price_records;
create policy "price_records_owner_insert" on public.price_records
  for insert
  to authenticated
  with check (auth.uid() = user_id);

drop policy if exists "price_records_owner_update" on public.price_records;
create policy "price_records_owner_update" on public.price_records
  for update
  to authenticated
  using (auth.uid() = user_id)
  with check (auth.uid() = user_id);

drop policy if exists "price_records_owner_delete" on public.price_records;
create policy "price_records_owner_delete" on public.price_records
  for delete
  to authenticated
  using (auth.uid() = user_id);

-- Políticas de shopping_lists
drop policy if exists "shopping_lists_member_select" on public.shopping_lists;
create policy "shopping_lists_member_select" on public.shopping_lists
  for select
  to authenticated
  using (auth.uid() = owner_id or auth.uid() = any(member_uids));

drop policy if exists "shopping_lists_owner_insert" on public.shopping_lists;
create policy "shopping_lists_owner_insert" on public.shopping_lists
  for insert
  to authenticated
  with check (auth.uid() = owner_id and auth.uid() = any(member_uids));

drop policy if exists "shopping_lists_member_update" on public.shopping_lists;
create policy "shopping_lists_member_update" on public.shopping_lists
  for update
  to authenticated
  using (auth.uid() = owner_id or auth.uid() = any(member_uids))
  with check (auth.uid() = owner_id or auth.uid() = any(member_uids));

drop policy if exists "shopping_lists_owner_delete" on public.shopping_lists;
create policy "shopping_lists_owner_delete" on public.shopping_lists
  for delete
  to authenticated
  using (auth.uid() = owner_id);

-- Políticas de shopping_list_items
drop policy if exists "shopping_list_items_member_all" on public.shopping_list_items;
create policy "shopping_list_items_member_all" on public.shopping_list_items
  for all
  to authenticated
  using (
    exists (
      select 1 from public.shopping_lists l
      where l.id = shopping_list_items.list_id
        and (l.owner_id = auth.uid() or auth.uid() = any(l.member_uids))
    )
  )
  with check (
    exists (
      select 1 from public.shopping_lists l
      where l.id = shopping_list_items.list_id
        and (l.owner_id = auth.uid() or auth.uid() = any(l.member_uids))
    )
  );

-- Função RPC segura para entrar em uma lista familiar via código de convite
create or replace function public.join_list_by_invite_code(p_invite_code text)
returns uuid
language plpgsql
security definer
set search_path = public
as $$
declare
  v_uid uuid := auth.uid();
  v_clean_code text := upper(trim(coalesce(p_invite_code, '')));
  v_list_id uuid;
  v_members uuid[];
begin
  if v_uid is null then
    raise exception 'Usuário não autenticado.';
  end if;

  if v_clean_code !~ '^[A-Z0-9-]{4,20}$' then
    raise exception 'Código de convite inválido.';
  end if;

  select id, member_uids
    into v_list_id, v_members
    from public.shopping_lists
   where invite_code = v_clean_code
   limit 1;

  if v_list_id is null then
    raise exception 'Convite não encontrado para o código informado.';
  end if;

  if v_uid = any(v_members) then
    return v_list_id;
  end if;

  if coalesce(array_length(v_members, 1), 0) >= 15 then
    raise exception 'Esta lista atingiu o limite de 15 participantes.';
  end if;

  update public.shopping_lists
     set member_uids = array_append(member_uids, v_uid),
         updated_at = now()
   where id = v_list_id;

  return v_list_id;
end;
$$;

revoke all on function public.join_list_by_invite_code(text) from public;
grant execute on function public.join_list_by_invite_code(text) to authenticated;

-- Garantir permissões na API PostgREST e atualizar cache do schema imediatamente
grant usage on schema public to anon, authenticated;
grant select, insert, update, delete on public.markets to authenticated;
grant select, insert, update, delete on public.price_records to authenticated;
grant select, insert, update, delete on public.shopping_lists to authenticated;
grant select, insert, update, delete on public.shopping_list_items to authenticated;

-- 5. Bucket público de fotos dos produtos no Supabase Storage (pasta por usuário + RLS)
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values (
  'product-photos',
  'product-photos',
  true,
  5242880,
  array['image/jpeg', 'image/png', 'image/webp']
)
on conflict (id) do update
  set public = excluded.public,
      file_size_limit = excluded.file_size_limit,
      allowed_mime_types = excluded.allowed_mime_types;

do $$
begin
  drop policy if exists "product_photos_public_read" on storage.objects;
  create policy "product_photos_public_read" on storage.objects
    for select
    to public
    using (bucket_id = 'product-photos');

  drop policy if exists "product_photos_owner_insert" on storage.objects;
  create policy "product_photos_owner_insert" on storage.objects
    for insert
    to authenticated
    with check (
      bucket_id = 'product-photos'
      and (storage.foldername(name))[1] = auth.uid()::text
    );

  drop policy if exists "product_photos_owner_update" on storage.objects;
  create policy "product_photos_owner_update" on storage.objects
    for update
    to authenticated
    using (
      bucket_id = 'product-photos'
      and (storage.foldername(name))[1] = auth.uid()::text
    )
    with check (
      bucket_id = 'product-photos'
      and (storage.foldername(name))[1] = auth.uid()::text
    );

  drop policy if exists "product_photos_owner_delete" on storage.objects;
  create policy "product_photos_owner_delete" on storage.objects
    for delete
    to authenticated
    using (
      bucket_id = 'product-photos'
      and (storage.foldername(name))[1] = auth.uid()::text
    );
exception
  when insufficient_privilege then
    raise notice 'Sem privilégio para criar policies em storage.objects via SQL Editor. Configure as policies do bucket product-photos em Storage > Policies no painel do Supabase.';
end $$;

-- 6. Publicação Supabase Realtime para sincronização automática em tempo real entre dispositivos
do $$
declare
  t text;
begin
  if exists (select 1 from pg_publication where pubname = 'supabase_realtime') then
    foreach t in array array['markets', 'price_records', 'shopping_lists', 'shopping_list_items'] loop
      if not exists (
        select 1
        from pg_publication_tables
        where pubname = 'supabase_realtime'
          and schemaname = 'public'
          and tablename = t
      ) then
        execute format('alter publication supabase_realtime add table public.%I', t);
      end if;
    end loop;
  end if;
exception
  when insufficient_privilege then
    raise notice 'Sem privilégio para alterar publication supabase_realtime. Ative Realtime para markets, price_records, shopping_lists e shopping_list_items em Database > Publications no painel do Supabase.';
end $$;

notify pgrst, 'reload schema';
