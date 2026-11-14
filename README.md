# FeiraJusta — Comparador Colaborativo de Preços & Listas Familiares

Documentação técnica e funcional completa do aplicativo Android **FeiraJusta**, desenvolvido em **Kotlin + Jetpack Compose (Material 3)** com arquitetura **Offline-First (Room)**, backend **Supabase (Auth, PostgreSQL, Storage e Realtime WebSocket)** e motor local de análise estatística de preços.

---

## 1. Visão Geral

O **FeiraJusta** ajuda consumidores e famílias a economizarem nas compras de supermercado por meio de quatro pilares integrados:

1. **Comparação Colaborativa entre Mercados**: qualquer usuário autenticado visualiza produtos, preços, ofertas e fotos cadastrados pela comunidade, identificando onde cada item está mais barato e qual a economia potencial.
2. **Cadastro Rápido com Fotos e Modo Offline**: registro ágil com sugestões automáticas de produtos já salvos, suporte a fotos pela Galeria ou Câmera (armazenadas no Supabase Storage) e fila local em SQLite (Room) caso o usuário esteja sem internet no corredor do mercado.
3. **Listas Compartilhadas da Família + Otimizador de Cesta**: criação de listas com código de convite (`FJ-XXXXXX`), cálculo automático de melhor supermercado único vs compra dividida e sugestões locais de substitutos mais baratos na mesma categoria.
4. **Sincronização em Tempo Real ("Ao vivo")**: cliente WebSocket próprio sobre OkHttp assinando eventos `postgres_changes` do Supabase Realtime, atualizando todos os dispositivos conectados segundos após um novo cadastro.

---

## 2. Identificação do Projeto Android

| Propriedade | Valor |
| :--- | :--- |
| **Nome do App** | `FeiraJusta` |
| **Application ID** | `com.aistudio.feirajusta.kptvxq` |
| **Namespace** | `com.example` |
| **Linguagem** | Kotlin (`JVM 11`) |
| **UI Framework** | Jetpack Compose + Material Design 3 |
| **Min SDK / Target / Compile SDK** | `24` / `36` / `36` |
| **Versão** | `1.0` (`versionCode = 1`) |
| **Idioma & Moeda** | Português (Brasil) · `R$` (`pt-BR`) |

---

## 3. Stack Tecnológica & Bibliotecas

O projeto foi mantido leve, sem dependências de IA/cloud proprietárias de terceiros nem SDKs pesados de rede:

| Camada | Tecnologia / Biblioteca | Finalidade |
| :--- | :--- | :--- |
| **Interface (UI)** | Jetpack Compose BOM `2024.09.00` + Material 3 | Telas declarativas, `PullToRefreshBox`, `ModalBottomSheet`, tema claro/escuro |
| **Navegação** | `androidx.navigation:navigation-compose` (`2.8.9`) | `NavHost` entre 4 abas principais com preservação de estado |
| **Estado & Ciclo de Vida** | `ViewModel`, `StateFlow`, `collectAsStateWithLifecycle` | Arquitetura reativa MVVM e observação de `ON_RESUME` |
| **Persistência Local** | Room `2.7.0` + KSP `2.3.5` | Fila offline `pending_prices` com migração `1 -> 2` (`localPhotoPath`) |
| **Preferências & Sessão** | `androidx.datastore:datastore-preferences` (`1.1.7`) | Armazenamento seguro de sessão JWT (`access_token`, `refresh_token`) |
| **Rede & Realtime** | OkHttp `4.10.0` + `HttpLoggingInterceptor` | Chamadas REST (Auth, PostgREST, Storage) e cliente WebSocket Phoenix v1 |
| **Imagens** | Coil Compose `2.7.0` + `ExifInterface` | Carregamento assíncrono (`http`, `content://`, `File`) e rotação/compressão JPEG |
| **Segredos** | `secrets-gradle-plugin` `2.0.1` | Injeção de `SUPABASE_URL` e `SUPABASE_ANON_KEY` via `BuildConfig` |
| **Testes Locais** | JUnit `4.13.2` + Robolectric `4.16.1` | Testes unitários e de plataforma Android na JVM sem emulador |

---

## 4. Arquitetura do Sistema

O aplicativo segue o padrão **MVVM (Model-View-ViewModel)** combinado com **Repository Pattern** e **Unidirectional Data Flow (UDF)**.

```
┌───────────────────────────────────────────────────────────────────────────┐
│                         Camada de Apresentação (Compose)                  │
│  AuthScreen · CompareScreen · ListsScreen · ScannerScreen · HistoryScreen │
│        ▲                       ▲                            ▲             │
│        │ StateFlow             │ Ações / Callbacks          │ ON_RESUME   │
└────────┼───────────────────────┼────────────────────────────┼─────────────┘
         │                       ▼                            │
┌────────┴────────────────────────────────────────────────────┴─────────────┐
│                             FeiraViewModel                                │
│  • Combina dados remotos + fila offline Room em `unifiedPricesState`      │
│  • Calcula `comparisonSummaries`, `priceSpikeAlerts` e `basketOptimization│
│  • Controla lifecycle da conexão Realtime e estado `isRefreshing`         │
└──────────────────────────┬──────────────────────────────────┬─────────────┘
                           │                                  │
                           ▼                                  ▼
┌──────────────────────────────────────┐   ┌────────────────────────────────┐
│            FeiraRepository           │   │      ConnectivityObserver      │
│  • PostgREST (CRUD + RPC)            │   │  • NetworkCallback (Online/Off)│
│  • Storage Upload / Delete (JPEG)    │   └────────────────────────────────┘
│  • Gatilhos de refresh por escopo    │
└───────┬──────────────────┬───────────┘
        │                  │
        ▼                  ▼
┌───────────────┐  ┌────────────────────────────────────────────────────────┐
│ Room Database │  │                Camada Supabase (OkHttp)                │
│ `pending_     │  │  • SupabaseAuthManager (GoTrue + Refresh Mutex)        │
│  prices` (v2) │  │  • SupabaseRealtimeClient (WebSocket `postgres_changes`│
│ + Fotos locais│  │  • SupabaseConfig (Normalização URL, Storage & Erros)  │
└───────────────┘  └────────────────────────────────────────────────────────┘
```

### Princípios Arquiteturais Chave
- **Unificação Transparente Online + Offline**: `FeiraViewModel.unifiedPricesState` mescla automaticamente os registros já persistidos no Supabase com os itens pendentes no Room (`isPendingSync = true`). Assim, o usuário vê imediatamente o preço e a foto que acabou de registrar mesmo sem internet.
- **Zero Dependência de IA Externa**: Toda inteligência de recomendação, templates rápidos e análise de tendências roda localmente em `FeiraAnalytics` com custo zero, latência instantânea e funcionamento 100% offline.

---

## 5. Estrutura do Projeto

```text
.
├── .env.example                          # Template de variáveis de ambiente
├── supabase_schema.sql                   # DDL completo: tabelas, RLS, RPC, Storage e Realtime
├── supabase_seed.sql                     # Script opcional de dados iniciais para portfólio
├── metadata.json                         # Metadados de identificação na plataforma
└── app/
    ├── build.gradle.kts                  # Configuração Gradle, Secrets plugin, KSP e dependências
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml       # Permissões (INTERNET, NETWORK_STATE, CAMERA) e FileProvider
        │   ├── res/
        │   │   ├── font/                 # Fontes locais: plus_jakarta_sans, inter, jetbrains_mono
        │   │   ├── values/strings.xml    # Textos principais de UI e autenticação
        │   │   └── xml/file_paths.xml    # Rotas do FileProvider (camera_captures/ e product_photos/)
        │   └── java/com/example/
        │       ├── MainActivity.kt               # Entrypoint, NavHost, TopAppBar, NavBar/NavRail, ON_RESUME
        │       ├── data/
        │       │   ├── local/
        │       │   │   └── FeiraLocalDatabase.kt # Room DB v2, Entity PendingPriceEntity, DAO e Migration
        │       │   ├── media/
        │       │   │   └── ProductPhotoHelper.kt # Decodificação segura, rotação EXIF, resize e JPEG
        │       │   ├── model/
        │       │   │   ├── FeiraModels.kt        # Modelos de domínio, formatBrl, parseBrlToCents, resolvePhotoModel
        │       │   │   └── FeiraAnalytics.kt     # Sugestões rápidas, alternativas de categoria e análise de tendência
        │       │   ├── network/
        │       │   │   └── ConnectivityObserver.kt # Monitoramento reativo de conectividade Android
        │       │   ├── repository/
        │       │   │   └── FeiraRepository.kt    # Orquestração REST, Storage, Room e eventos Realtime
        │       │   └── supabase/
        │       │       ├── SupabaseConfig.kt     # URLs, Storage paths, mapeamento de erros GoTrue/PostgREST/Storage
        │       │       ├── SupabaseAuthManager.kt# Login/Cadastro, DataStore e renovação thread-safe de JWT
        │       │       └── SupabaseRealtimeClient.kt # Cliente WebSocket Phoenix v1 para `postgres_changes`
        │       └── ui/
        │           ├── auth/AuthScreen.kt        # Tela de login e criação de conta no Supabase
        │           ├── components/FeiraCommonComponents.kt # SyncStatusPill ("Ao vivo"), gráfico Canvas, Thumbnail, Sheet
        │           ├── screens/
        │           │   ├── CompareScreen.kt      # Comparador de preços, busca, categorias, alertas e mercados
        │           │   ├── ListsScreen.kt        # Listas compartilhadas, convite, otimizador e substitutos
        │           │   ├── ScannerScreen.kt      # Aba Registrar: templates rápidos, formulário, foto e fila offline
        │           │   └── HistoryScreen.kt      # Aba Histórico: KPIs, gráfico, análise estatística e galeria
        │           └── theme/
        │               ├── Color.kt              # Paleta Light/Dark (Grafite, Branco, Esmeralda, Coral, Âmbar)
        │               ├── Theme.kt              # Configuração MaterialTheme
        │               └── Type.kt               # Tipografia Plus Jakarta Sans, Inter e JetBrains Mono
        └── test/java/com/example/
            ├── ExampleUnitTest.kt
            ├── ExampleRobolectricTest.kt
            └── data/FeiraSupabaseAndLogicTest.kt # Suíte de testes unitários e Robolectric
```

---

## 6. Banco de Dados Supabase (PostgreSQL + RLS + Storage + Realtime)

O script `supabase_schema.sql` provisiona toda a infraestrutura de backend de forma idempotente.

### 6.1 Tabelas e Relacionamentos

1. **`public.markets`** — Supermercados cadastrados pelos usuários
   - `id` (`uuid`, PK), `user_id` (`uuid` FK `auth.users`), `name` (`1..80`), `neighborhood` (`<= 80`), `created_at`, `updated_at`.
2. **`public.price_records`** — Registros de preços e ofertas
   - `id` (`uuid`, PK), `user_id` (`uuid` FK `auth.users`), `product_name` (`1..100`), `normalized_name` (`1..100`), `brand` (`<= 60`), `category` (`<= 40`), `unit_label` (`<= 30`), `price_cents` (`bigint`, `1..10.000.000`), `market_id` (`text`), `market_name` (`1..80`), `is_promo` (`boolean`), `photo_url` (`text <= 500`), `recorded_at_millis` (`bigint`), `created_at`, `updated_at`.
   - Índices em `user_id` e `recorded_at_millis desc`.
3. **`public.shopping_lists`** — Listas familiares compartilhadas
   - `id` (`uuid`, PK), `owner_id` (`uuid` FK `auth.users`), `member_uids` (`uuid[]`, 1..15 membros), `title` (`1..80`), `invite_code` (`^[A-Z0-9-]{4,20}$`, `unique`), `created_at`, `updated_at`.
   - Índice GIN em `member_uids` para filtro eficiente de listas por participante.
4. **`public.shopping_list_items`** — Itens das listas compartilhadas
   - `id` (`uuid`, PK), `list_id` (`uuid` FK `shopping_lists` on delete cascade), `product_name`, `normalized_name`, `quantity` (`1..999`), `unit_label`, `checked` (`boolean`), `added_by_uid` (`uuid` FK `auth.users`), `created_at`, `updated_at`.

### 6.2 Políticas de Segurança (Row Level Security — RLS)

| Tabela | `SELECT` | `INSERT` | `UPDATE` | `DELETE` |
| :--- | :--- | :--- | :--- | :--- |
| **`markets`** | Qualquer usuário `authenticated` (`true`) | Autor (`auth.uid() = user_id`) | Autor (`auth.uid() = user_id`) | Autor (`auth.uid() = user_id`) |
| **`price_records`** | Qualquer usuário `authenticated` (`true`) | Autor (`auth.uid() = user_id`) | Autor (`auth.uid() = user_id`) | Autor (`auth.uid() = user_id`) |
| **`shopping_lists`** | Dono ou membro (`owner_id` ou `any(member_uids)`) | Dono presente em `member_uids` | Dono ou membro | Apenas dono (`auth.uid() = owner_id`) |
| **`shopping_list_items`** | Membros da lista pai | Membros da lista pai | Membros da lista pai | Membros da lista pai |

### 6.3 Função RPC Segura (`join_list_by_invite_code`)

- Executada com `security definer` e `set search_path = public` (acesso concedido apenas a `authenticated`).
- Valida se o usuário está autenticado, normaliza e valida o formato do código (`^[A-Z0-9-]{4,20}$`), verifica limite máximo de **15 participantes** e adiciona `auth.uid()` ao array `member_uids` sem expor todas as listas via `SELECT`.

### 6.4 Supabase Storage (`product-photos`)

- **Bucket**: `product-photos` (público para leitura direta por `AsyncImage`, limite de **5 MB** por arquivo, tipos MIME aceitos: `image/jpeg`, `image/png`, `image/webp`).
- **Organização de pastas**: `<user_id>/<uuid>.jpg`.
- **Policies em `storage.objects`**:
  - Leitura (`SELECT`): `public` quando `bucket_id = 'product-photos'`.
  - Escrita/Edição/Exclusão (`INSERT`, `UPDATE`, `DELETE`): `authenticated` restrito à pasta do próprio usuário (`(storage.foldername(name))[1] = auth.uid()::text`).
  - *Nota*: O bloco de `storage.objects` no `supabase_schema.sql` possui tratamento de `insufficient_privilege` para não abortar o script SQL Editor caso o projeto exija configuração dessas policies pelo menu **Storage > Policies**.

### 6.5 Publicação Realtime (`supabase_realtime`)

- O script adiciona de forma idempotente `markets`, `price_records`, `shopping_lists` e `shopping_list_items` à publicação `supabase_realtime`, permitindo broadcast instantâneo de `INSERT`, `UPDATE` e `DELETE` via WebSocket.

---

## 7. Autenticação & Gerenciamento de Sessão

Implementado em `SupabaseAuthManager`:
- **Fluxos suportados**: Login (`POST /auth/v1/token?grant_type=password`), Cadastro (`POST /auth/v1/signup` com suporte a `ConfirmationRequired` caso a confirmação de e-mail esteja ativa no projeto) e Logout (`POST /auth/v1/logout`).
- **Persistência local**: `DataStore Preferences` (`feira_supabase_auth`) guarda `access_token`, `refresh_token`, `user_id`, `email`, `display_name` e `expires_at`.
- **Renovação automática e segura**: `getValidAccessToken(forceRefresh)` usa `Mutex` (`refreshMutex`) para evitar chamadas concorrentes de refresh; renova tokens a menos de 60 segundos do vencimento ou após qualquer resposta HTTP `401`.
- **Resiliência offline**: Erros de rede (`IOException`) durante o refresh preservam a sessão em cache para que o usuário continue consultando e salvando na fila offline Room; apenas `400/401/403` invalidam a sessão local.
- **Normalização de URL (`SupabaseConfig.normalizeBaseUrl`)**: Corrige automaticamente URLs coladas com barras finais, sufixos `/rest/v1` ou `/auth/v1`, ou URLs copiadas do Dashboard (`https://supabase.com/dashboard/project/<ref>/...`), prevenindo erros como `"Invalid path specified in request URL"`.

---

## 8. Motor de Sincronização em Tempo Real & Offline-First

### 8.1 Fluxo em Tempo Real (`SupabaseRealtimeClient`)
1. Ao autenticar e estar online, abre WebSocket em:
   `wss://<projeto>.supabase.co/realtime/v1/websocket?apikey=<anon_key>&vsn=1.0.0` (com `readTimeout = 0` no `OkHttpClient` dedicado para não expirar conexões ociosas).
2. Envia mensagem `phx_join` no tópico `realtime:feira-sync` com o JWT do usuário e inscrição `postgres_changes` (`event: "*"`, `schema: "public"`) para as 4 tabelas do sistema.
3. Envia heartbeat Phoenix (`{"topic":"phoenix","event":"heartbeat",...}`) a cada **20 segundos**.
4. Ao receber qualquer evento `postgres_changes`, roteia o escopo correspondente (`markets`, `price_records`, `shopping_lists`, `shopping_list_items`) para o `MutableSharedFlow` de `FeiraRepository`, que executa imediatamente o re-fetch tipado.
5. **Resiliência em múltiplas camadas**:
   - **Atualização de JWT**: Se a sessão renovar enquanto conectado, envia o evento `access_token` no canal aberto.
   - **Reconexão Exponencial**: Em quedas, reconecta com backoff (`2s -> 4s -> 8s -> 15s -> 30s`).
   - **Fallback Polling**: Enquanto o WebSocket estiver desconectado, um ticker de segurança consulta a API a cada 15–30s (e fica silencioso quando o WebSocket está ativo).
   - **Ciclo de Vida & Gestos**: `ON_RESUME` em `MainActivity` e *pull-to-refresh* (`PullToRefreshBox`) nas abas garantem atualização ao retornar ao app ou puxar manualmente.

### 8.2 Fluxo de Fotos & Fila Offline (`Room` + `ProductPhotoHelper`)
1. Usuário escolhe imagem via **Android Photo Picker** (`PickVisualMedia`, com fallback `OpenDocument`) ou tira foto via **Câmera** (`TakePicture` gravando em `cacheDir/camera_captures/` via `FileProvider`, solicitando `CAMERA` em runtime).
2. `ProductPhotoHelper.compressAndSaveToAppStorage`:
   - Lê dimensões com `inJustDecodeBounds = true` e valida `outWidth > 0 && outHeight > 0`.
   - Aplica `inSampleSize`, lê orientação **EXIF**, rotaciona o bitmap se necessário, redimensiona para no máximo **1280px** no maior lado e comprime em **JPEG 82%** dentro de `filesDir/product_photos/`.
   - Remove o arquivo temporário da câmera.
3. **Envio Online vs Offline**:
   - **Online**: faz upload binário `POST /storage/v1/object/product-photos/<uid>/<uuid>.jpg` com header `x-upsert: true`, insere o registro no Postgres com a `photo_url` pública retornada e apaga a cópia local.
   - **Offline ou falha transitória de rede**: salva `PendingPriceEntity` no Room (`feirajusta_offline.db`, versão 2) preservando `localPhotoPath`. Assim que a internet retorna (`ConnectivityObserver`) ou o usuário toca em **Sincronizar**, a fila sobe em ordem cronológica (foto primeiro, depois registro) e limpa os arquivos locais enviados.
4. **Limpeza na exclusão**: Ao excluir um registro próprio com foto no Histórico, o app remove a linha no Postgres e dispara `DELETE` no objeto correspondente do Supabase Storage.

---

## 9. Funcionalidades por Tela & Motor Estatístico (`FeiraAnalytics`)

### 9.1 Barra Superior & Navegação Adaptativa
- **Compact (< 600dp)**: `NavigationBar` inferior com 4 abas (**Comparar**, **Listas**, **Registrar**, **Histórico**) + FAB rápido na aba Comparar.
- **Medium / Expanded (>= 600dp)**: `NavigationRail` lateral esquerda e conteúdo centralizado com `widthIn(max = 680.dp)`.
- **`SyncStatusPill`**: Exibe **"Ao vivo"** (com ponto pulsante quando WebSocket está conectado), **"Sincronizado"**, **"Sincronizando..."**, **"Sincronizar (N)"**, **"Modo Offline"** ou **"Offline · N pendente(s)"**. Tocar enquanto online força atualização imediata.

### 9.2 Aba Comparar (`CompareScreen`)
- Busca instantânea por produto, marca ou supermercado + filtro horizontal por categoria (`Todos`, `Mercearia`, `Laticínios`, `Hortifrúti`, `Açougue`, `Bebidas`, `Limpeza`, `Higiene`, `Padaria`).
- **Banner de Alta Atípica**: Destaca automaticamente produtos cujo último preço subiu **>= 15%** frente à média histórica anterior; tocar abre direto o histórico daquele item.
- **Barra de Mercados**: Lista os supermercados compartilhados e permite cadastrar novos estabelecimentos.
- **Cards de Comparação + Sheet Detalhado (`ProductComparisonSheet`)**: Exibe miniatura clicável (abre `FullScreenPhotoDialog`), menor preço atual, mercado vencedor, economia potencial vs maior preço e lista completa de cotações por mercado com botão para adicionar à lista de compras.

### 9.3 Aba Listas (`ListsScreen`)
- Criação de novas listas compartilhadas, ingresso via código de convite (`FJ-XXXXXX`) e compartilhamento nativo via `Intent.ACTION_SEND`.
- **Otimizador da Compra (`BasketOptimizationSummary`)**:
  - Calcula o **Melhor mercado único** (priorizando maior cobertura de itens e menor total).
  - Calcula o **Total dividindo nos mais baratos** e destaca a economia extra em `R$` frente ao melhor mercado único.
- **Alternativas na Categoria (`FeiraAnalytics.findCategoryAlternatives`)**: Ao tocar em "Comparar" em um item da lista, analisa os produtos da mesma categoria salvos no banco (ou categoria inferida por palavras-chave) que custam menos que o valor de referência e exibe até 5 substitutos ordenados por preço com `%` de economia estimada.

### 9.4 Aba Registrar (`ScannerScreen`)
- **Sugestões dos Produtos Salvos**: Chips interativos gerados a partir do histórico (filtrados em tempo real conforme o usuário digita) que preenchem produto, marca, categoria e unidade com um toque e mostram o último preço e mercado registrado.
- Formulário completo com validação de valor em Reais (`parseBrlToCents`), seleção de categoria, unidade/embalagem, supermercado cadastrado ou digitado, switch de oferta promocional e card de **Foto do produto (opcional)** com prévia, zoom, troca e remoção.
- Seção dedicada da **Fila offline** com miniatura das fotos aguardando envio e botão de sincronização manual.

### 9.5 Aba Histórico (`HistoryScreen`)
- Seletor horizontal de produtos comparados + cards KPI (**Mínimo**, **Média**, **Último**).
- **Gráfico de Curva de Variação (`PriceHistoryLineChart`)**: Desenhado nativamente em Jetpack Compose `Canvas` com gradiente, linhas de referência tracejadas e datas mínimo/máximo.
- **Análise Estatística de Variação (`FeiraAnalytics.analyzePriceTrend`)**: Classifica automaticamente o último preço em `ALTO` (`>= +15%`), `ATENCAO` (`>= +5%`) ou `NORMAL` frente à média anterior, mostrando amplitude (`máx - mín`), frequência em oferta e resumo textual explicativo.
- **Galeria "Fotos registradas"**: Faixa horizontal com todas as fotos do produto selecionado + lista cronológica identificando registros próprios (com botão de excluir) e registros da **Comunidade**.

---

## 10. Design System, Tipografia & Acessibilidade

- **Paleta Editorial (`Color.kt` / `Theme.kt`)**:
  - **Modo Claro**: Fundo `#FAFAFB`, Cards `#FFFFFF`, Bordas `#E3E6EC`, Grafite `#181A1F`, Destaque Economia Esmeralda `#0F9D68`, Alerta Coral `#D9483B`, Offline Âmbar `#D97706`.
  - **Modo Escuro**: Fundo `#111317`, Cards `#181B21`, Bordas `#2F3440`, Esmeralda `#34D399`, Coral `#F87171`.
- **Tipografia Bundled (`res/font/` + `Type.kt`)**:
  - `Plus Jakarta Sans`: títulos e cabeçalhos.
  - `Inter`: corpo de texto, rótulos e formulários.
  - `JetBrains Mono`: valores monetários (`R$`), códigos de convite e rótulos técnicos numéricos.
- **Acessibilidade & Edge-to-Edge**:
  - `enableEdgeToEdge()` com `WindowInsets.safeDrawing` e `WindowInsets.navigationBars`.
  - `minimumInteractiveComponentSize()` (mínimo `48.dp`), `contentDescription` em imagens/ícones e `Modifier.testTag(...)` nos elementos principais.

---

## 11. Permissões & Conformidade Google Play

Definidas em `AndroidManifest.xml`:
- `android.permission.INTERNET` e `android.permission.ACCESS_NETWORK_STATE` (normais, install-time).
- `android.permission.CAMERA` (solicitada em runtime somente quando o usuário toca em **Câmera**, com `android:required="false"` em `<uses-feature>` para compatibilidade com dispositivos sem câmera).
- **Zero permissões amplas de armazenamento**: A seleção na galeria usa o **Android Photo Picker** (`PickVisualMedia`), em conformidade com as políticas do Google Play (sem `READ_MEDIA_IMAGES` ou `READ_EXTERNAL_STORAGE`).

---

## 12. Guia de Configuração, Execução e Testes

### 12.1 Configurar Variáveis do Supabase
Não insira chaves diretamente no código-fonte. Defina no painel **Secrets** (ou `.env` local baseado em `.env.example`):

```env
SUPABASE_URL=https://SEU_PROJECT_ID.supabase.co
SUPABASE_ANON_KEY=SUA_CHAVE_ANON_PUBLICA
```

### 12.2 Provisionar o Banco no Supabase
1. Acesse o **SQL Editor** do seu projeto no painel do Supabase.
2. Execute integralmente o arquivo `supabase_schema.sql` para criar tabelas, índices, políticas RLS, RPC de convite, bucket `product-photos` e inclusão na publicação `supabase_realtime`.
3. *(Opcional — para demonstração/portfólio)* Após criar ao menos 1 usuário no aplicativo, execute `supabase_seed.sql` para popular 4 supermercados, dezenas de preços históricos (incluindo alertas de alta) e 2 listas familiares prontas.
4. Caso o SQL Editor emita aviso de privilégio em `storage.objects` ou `supabase_realtime`, finalize esses dois pontos pelo painel visual:
   - **Storage > Policies (`product-photos`)**: leitura `SELECT` para `public`; escrita (`INSERT`, `UPDATE`, `DELETE`) para `authenticated` verificando `(storage.foldername(name))[1] = auth.uid()::text`.
   - **Database > Publications (`supabase_realtime`)**: habilitar `markets`, `price_records`, `shopping_lists` e `shopping_list_items`.

### 12.3 Compilação e Testes Locais (JVM / Robolectric)

```bash
# Compilar o APK de debug
gradle assembleDebug

# Rodar a suíte completa de testes unitários e Robolectric (11 testes)
gradle :app:testDebugUnitTest
```

Os testes em `FeiraSupabaseAndLogicTest` validam automaticamente:
- Normalização de URLs do Supabase e validação de credenciais.
- Tradução de erros GoTrue (Auth), PostgREST (`PGRST205`) e Storage (`Bucket not found`).
- Construção de URL pública, extração de path no Storage e resolução de modelos de foto.
- Geração da URL WebSocket e parsing dos payloads Phoenix (`phx_join`, `heartbeat`, `postgres_changes` de `INSERT` e `DELETE`).
- Compressão e gravação local de fotos via `ProductPhotoHelper` (Robolectric SDK 36).
- Parser/formatação BRL (`R$`), normalização de nomes sem acentos, geração de código de convite (`FJ-XXXXXX`), alternativas de categoria e classificação estatística de tendências.
