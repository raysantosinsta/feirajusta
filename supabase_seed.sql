-- FeiraJusta — Script SQL completo para popular o banco de dados no Supabase
-- Execute no SQL Editor do Supabase DEPOIS de ter criado ao menos 1 conta no app
-- e já ter executado o supabase_schema.sql.

do $$
declare
  v_user_id uuid;
  v_m1 uuid;
  v_m2 uuid;
  v_m3 uuid;
  v_m4 uuid;
  v_list_semana uuid;
  v_list_churrasco uuid;
begin
  -- Seleciona o usuário cadastrado mais recente (ou troque pelo e-mail desejado)
  select id
    into v_user_id
    from auth.users
   -- where lower(email) = 'seu-email@exemplo.com'
   order by created_at desc
   limit 1;

  if v_user_id is null then
    raise exception 'Nenhum usuário encontrado em auth.users. Crie sua conta no app antes de rodar este script.';
  end if;

  -- 1. Mercados
  insert into public.markets (user_id, name, neighborhood)
  values (v_user_id, 'Atacadão Central', 'Centro')
  returning id into v_m1;

  insert into public.markets (user_id, name, neighborhood)
  values (v_user_id, 'Assaí Bairro Sul', 'Zona Sul')
  returning id into v_m2;

  insert into public.markets (user_id, name, neighborhood)
  values (v_user_id, 'Carrefour Express', 'Jardins')
  returning id into v_m3;

  insert into public.markets (user_id, name, neighborhood)
  values (v_user_id, 'Supermercado Guanabara', 'Vila Nova')
  returning id into v_m4;

  -- 2. Registros de preços + histórico (últimos 25 dias, incluindo altas para alertas)
  insert into public.price_records (
    user_id, product_name, normalized_name, brand, category, unit_label,
    price_cents, market_id, market_name, is_promo, recorded_at_millis
  ) values
    -- Arroz Tipo 1 (5 kg)
    (v_user_id, 'Arroz Tipo 1', 'arroz tipo 1', 'Camil', 'Mercearia', '5 kg', 2790, v_m1::text, 'Atacadão Central', false, (extract(epoch from (now() - interval '20 days')) * 1000)::bigint),
    (v_user_id, 'Arroz Tipo 1', 'arroz tipo 1', 'Camil', 'Mercearia', '5 kg', 2490, v_m1::text, 'Atacadão Central', true,  (extract(epoch from (now() - interval '1 day')) * 1000)::bigint),
    (v_user_id, 'Arroz Tipo 1', 'arroz tipo 1', 'Camil', 'Mercearia', '5 kg', 2650, v_m2::text, 'Assaí Bairro Sul', false, (extract(epoch from (now() - interval '2 days')) * 1000)::bigint),
    (v_user_id, 'Arroz Tipo 1', 'arroz tipo 1', 'Tio João', 'Mercearia', '5 kg', 3190, v_m3::text, 'Carrefour Express', false, (extract(epoch from (now() - interval '1 day')) * 1000)::bigint),
    (v_user_id, 'Arroz Tipo 1', 'arroz tipo 1', 'Camil', 'Mercearia', '5 kg', 2590, v_m4::text, 'Supermercado Guanabara', false, (extract(epoch from (now() - interval '3 days')) * 1000)::bigint),

    -- Feijão Carioca (1 kg)
    (v_user_id, 'Feijão Carioca', 'feijao carioca', 'Kicaldo', 'Mercearia', '1 kg', 749, v_m1::text, 'Atacadão Central', false, (extract(epoch from (now() - interval '15 days')) * 1000)::bigint),
    (v_user_id, 'Feijão Carioca', 'feijao carioca', 'Kicaldo', 'Mercearia', '1 kg', 689, v_m4::text, 'Supermercado Guanabara', true, (extract(epoch from (now() - interval '2 days')) * 1000)::bigint),
    (v_user_id, 'Feijão Carioca', 'feijao carioca', 'Camil', 'Mercearia', '1 kg', 899, v_m3::text, 'Carrefour Express', false, (extract(epoch from (now() - interval '1 day')) * 1000)::bigint),

    -- Café Torrado e Moído (500 g) — histórico crescente
    (v_user_id, 'Café Torrado e Moído', 'cafe torrado e moido', 'Pilão', 'Mercearia', '500 g', 1790, v_m2::text, 'Assaí Bairro Sul', false, (extract(epoch from (now() - interval '24 days')) * 1000)::bigint),
    (v_user_id, 'Café Torrado e Moído', 'cafe torrado e moido', 'Pilão', 'Mercearia', '500 g', 1950, v_m2::text, 'Assaí Bairro Sul', false, (extract(epoch from (now() - interval '12 days')) * 1000)::bigint),
    (v_user_id, 'Café Torrado e Moído', 'cafe torrado e moido', 'Pilão', 'Mercearia', '500 g', 1890, v_m1::text, 'Atacadão Central', true,  (extract(epoch from (now() - interval '2 days')) * 1000)::bigint),
    (v_user_id, 'Café Torrado e Moído', 'cafe torrado e moido', 'Melitta', 'Mercearia', '500 g', 2390, v_m3::text, 'Carrefour Express', false, (extract(epoch from (now() - interval '1 day')) * 1000)::bigint),

    -- Azeite Extra Virgem (500 mL) — alerta de alta relevante (+38%)
    (v_user_id, 'Azeite Extra Virgem', 'azeite extra virgem', 'Gallo', 'Mercearia', '500 g', 3190, v_m1::text, 'Atacadão Central', false, (extract(epoch from (now() - interval '25 days')) * 1000)::bigint),
    (v_user_id, 'Azeite Extra Virgem', 'azeite extra virgem', 'Gallo', 'Mercearia', '500 g', 3290, v_m2::text, 'Assaí Bairro Sul', false, (extract(epoch from (now() - interval '12 days')) * 1000)::bigint),
    (v_user_id, 'Azeite Extra Virgem', 'azeite extra virgem', 'Gallo', 'Mercearia', '500 g', 4490, v_m3::text, 'Carrefour Express', false, (extract(epoch from (now() - interval '6 hours')) * 1000)::bigint),

    -- Óleo de Soja (900 mL)
    (v_user_id, 'Óleo de Soja', 'oleo de soja', 'Liza', 'Mercearia', '1 un', 629, v_m2::text, 'Assaí Bairro Sul', true, (extract(epoch from (now() - interval '3 days')) * 1000)::bigint),
    (v_user_id, 'Óleo de Soja', 'oleo de soja', 'Soya', 'Mercearia', '1 un', 749, v_m3::text, 'Carrefour Express', false, (extract(epoch from (now() - interval '1 day')) * 1000)::bigint),

    -- Leite Integral (1 L) — histórico + variação
    (v_user_id, 'Leite Integral', 'leite integral', 'Italac', 'Laticínios', '1 L', 499, v_m4::text, 'Supermercado Guanabara', false, (extract(epoch from (now() - interval '18 days')) * 1000)::bigint),
    (v_user_id, 'Leite Integral', 'leite integral', 'Italac', 'Laticínios', '1 L', 469, v_m4::text, 'Supermercado Guanabara', true,  (extract(epoch from (now() - interval '1 day')) * 1000)::bigint),
    (v_user_id, 'Leite Integral', 'leite integral', 'Parmalat', 'Laticínios', '1 L', 589, v_m3::text, 'Carrefour Express', false, (extract(epoch from (now() - interval '2 days')) * 1000)::bigint),
    (v_user_id, 'Leite Integral', 'leite integral', 'Piracanjuba', 'Laticínios', '1 L', 489, v_m1::text, 'Atacadão Central', false, (extract(epoch from (now() - interval '3 days')) * 1000)::bigint),

    -- Peito de Frango (1 kg)
    (v_user_id, 'Peito de Frango', 'peito de frango', 'Sadia', 'Açougue', '1 kg', 1890, v_m2::text, 'Assaí Bairro Sul', true, (extract(epoch from (now() - interval '2 days')) * 1000)::bigint),
    (v_user_id, 'Peito de Frango', 'peito de frango', 'Seara', 'Açougue', '1 kg', 2390, v_m3::text, 'Carrefour Express', false, (extract(epoch from (now() - interval '1 day')) * 1000)::bigint),
    (v_user_id, 'Peito de Frango', 'peito de frango', 'Sadia', 'Açougue', '1 kg', 1990, v_m4::text, 'Supermercado Guanabara', false, (extract(epoch from (now() - interval '4 days')) * 1000)::bigint),

    -- Carne Bovina Alcatra (1 kg)
    (v_user_id, 'Alcatra Bovina', 'alcatra bovina', 'Friboi', 'Açougue', '1 kg', 3890, v_m4::text, 'Supermercado Guanabara', true, (extract(epoch from (now() - interval '2 days')) * 1000)::bigint),
    (v_user_id, 'Alcatra Bovina', 'alcatra bovina', 'Mataboi', 'Açougue', '1 kg', 4490, v_m3::text, 'Carrefour Express', false, (extract(epoch from (now() - interval '1 day')) * 1000)::bigint),

    -- Banana Prata (1 kg)
    (v_user_id, 'Banana Prata', 'banana prata', 'Horti', 'Hortifrúti', '1 kg', 549, v_m4::text, 'Supermercado Guanabara', false, (extract(epoch from (now() - interval '2 days')) * 1000)::bigint),
    (v_user_id, 'Banana Prata', 'banana prata', 'Horti', 'Hortifrúti', '1 kg', 799, v_m3::text, 'Carrefour Express', false, (extract(epoch from (now() - interval '1 day')) * 1000)::bigint),

    -- Tomate Italiano (1 kg) — alerta de alta recente (+45%)
    (v_user_id, 'Tomate Italiano', 'tomate italiano', 'Horti', 'Hortifrúti', '1 kg', 649, v_m4::text, 'Supermercado Guanabara', false, (extract(epoch from (now() - interval '14 days')) * 1000)::bigint),
    (v_user_id, 'Tomate Italiano', 'tomate italiano', 'Horti', 'Hortifrúti', '1 kg', 699, v_m1::text, 'Atacadão Central', false, (extract(epoch from (now() - interval '7 days')) * 1000)::bigint),
    (v_user_id, 'Tomate Italiano', 'tomate italiano', 'Horti', 'Hortifrúti', '1 kg', 980, v_m3::text, 'Carrefour Express', false, (extract(epoch from (now() - interval '4 hours')) * 1000)::bigint),

    -- Suco de Laranja Integral (1 L)
    (v_user_id, 'Suco de Laranja Integral', 'suco de laranja integral', 'Natural One', 'Bebidas', '1 L', 1290, v_m1::text, 'Atacadão Central', true, (extract(epoch from (now() - interval '2 days')) * 1000)::bigint),
    (v_user_id, 'Suco de Laranja Integral', 'suco de laranja integral', 'Natural One', 'Bebidas', '1 L', 1590, v_m3::text, 'Carrefour Express', false, (extract(epoch from (now() - interval '1 day')) * 1000)::bigint),

    -- Pão de Forma Integral (500 g)
    (v_user_id, 'Pão de Forma Integral', 'pao de forma integral', 'Wickbold', 'Padaria', '500 g', 849, v_m4::text, 'Supermercado Guanabara', false, (extract(epoch from (now() - interval '2 days')) * 1000)::bigint),
    (v_user_id, 'Pão de Forma Integral', 'pao de forma integral', 'Pullman', 'Padaria', '500 g', 999, v_m3::text, 'Carrefour Express', false, (extract(epoch from (now() - interval '1 day')) * 1000)::bigint),

    -- Sabão em Pó (1 kg)
    (v_user_id, 'Sabão em Pó', 'sabao em po', 'Omo', 'Limpeza', '1 kg', 1690, v_m1::text, 'Atacadão Central', true, (extract(epoch from (now() - interval '3 days')) * 1000)::bigint),
    (v_user_id, 'Sabão em Pó', 'sabao em po', 'Omo', 'Limpeza', '1 kg', 2190, v_m3::text, 'Carrefour Express', false, (extract(epoch from (now() - interval '1 day')) * 1000)::bigint),

    -- Papel Higiênico (pct)
    (v_user_id, 'Papel Higiênico', 'papel higienico', 'Neve', 'Higiene', 'pct', 1990, v_m2::text, 'Assaí Bairro Sul', false, (extract(epoch from (now() - interval '2 days')) * 1000)::bigint),
    (v_user_id, 'Papel Higiênico', 'papel higienico', 'Neve', 'Higiene', 'pct', 2490, v_m3::text, 'Carrefour Express', false, (extract(epoch from (now() - interval '1 day')) * 1000)::bigint);

  -- 3. Listas compartilhadas em família
  insert into public.shopping_lists (owner_id, member_uids, title, invite_code)
  values (v_user_id, array[v_user_id], 'Compra da Semana', 'FJ-CASA01')
  on conflict (invite_code) do update set updated_at = now()
  returning id into v_list_semana;

  insert into public.shopping_lists (owner_id, member_uids, title, invite_code)
  values (v_user_id, array[v_user_id], 'Churrasco & Bebidas', 'FJ-CHUR02')
  on conflict (invite_code) do update set updated_at = now()
  returning id into v_list_churrasco;

  -- 4. Itens das listas (com nomes normalizados iguais aos preços para otimização automática)
  insert into public.shopping_list_items (
    list_id, product_name, normalized_name, quantity, unit_label, checked, added_by_uid
  ) values
    (v_list_semana, 'Arroz Tipo 1', 'arroz tipo 1', 1, '5 kg', false, v_user_id),
    (v_list_semana, 'Feijão Carioca', 'feijao carioca', 2, '1 kg', false, v_user_id),
    (v_list_semana, 'Leite Integral', 'leite integral', 6, '1 L', false, v_user_id),
    (v_list_semana, 'Café Torrado e Moído', 'cafe torrado e moido', 1, '500 g', true, v_user_id),
    (v_list_semana, 'Peito de Frango', 'peito de frango', 2, '1 kg', false, v_user_id),
    (v_list_semana, 'Sabão em Pó', 'sabao em po', 1, '1 kg', false, v_user_id),
    (v_list_semana, 'Banana Prata', 'banana prata', 2, '1 kg', false, v_user_id),

    (v_list_churrasco, 'Alcatra Bovina', 'alcatra bovina', 2, '1 kg', false, v_user_id),
    (v_list_churrasco, 'Suco de Laranja Integral', 'suco de laranja integral', 2, '1 L', false, v_user_id),
    (v_list_churrasco, 'Tomate Italiano', 'tomate italiano', 1, '1 kg', false, v_user_id),
    (v_list_churrasco, 'Papel Higiênico', 'papel higienico', 1, 'pct', false, v_user_id);
end $$;
