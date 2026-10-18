# fedstat

Clojure/babashka-библиотека для загрузки данных с [fedstat.ru](https://www.fedstat.ru) — ЕМИСС (Единая межведомственная информационно-статистическая система Росстата. Порт R-пакета [fedstatAPIr](https://github.com/DenchPokepon/fedstatAPIr) v1.1.0 (CRAN, лицензия MIT, авторы Denis Krylov, Dmitry Kibalnikov) с сохранением семантики: модель данных, правила фильтрации, тексты ошибок и предупреждений повторяют оригинал.

Целевая платформа — [babashka](https://babashka.org) (bb ≥ 1.13): библиотека работает как pod-free код внутри bb-скриптов. Параллельно поддерживается запуск на JVM (Clojure CLI) с тем же исходником — нужна только для полноценных тестов через `clojure -M:test` и сборки jar.

## Статус и известные проблемы

- **GET-эндпоинты работают** против live-сайта: страница индикатора, каталог `/organizations/`.
- **Полный цикл живьём (проверено 2026-10-16):** `dev/examples/example_broad_money.clj` — каталог → `get-data-ids` → POST → SDMX-таблица (индикатор 62278).
- **CSRF-токены одноразовые:** при повторном POST нужен свежий токен — `load-with-filters` делает это автоматически (ретрай цикла с новым GET).

## Требования

- babashka ≥ 1.13 (`bb --version`) — для запуска и основных тестов.
- Clojure CLI (`clojure`) + JVM — только для `clojure -M:test` и `bb run jar`.
- Доступ в интернет — только для live-проверок; весь тест-сьют офлайн (фикстуры + фейковый транспорт).

## Быстрый старт

```clojure
(require '[fedstat.core :as fedstat]
         '[fedstat.transport :as transport])

;; Каталог индикаторов: [{:department :group :id :title :hidden} ...]
(fedstat.indicators/indicator-info transport/default-transport {})

;; data-ids индикатора: {:rows [...] :meta {...}}
(def data-ids
  (fedstat/get-data-ids "31074" {:transport transport/default-transport}))

;; Фильтрация (семантика как в R: "*" = все значения, однозначные
;; поля и "Показатель" добавляются автоматически)
(def filtered
  (fedstat.filters/filter-data-ids data-ids {"Субъект РФ" "Приморский край"}
                                   {}))

;; Полный цикл get -> filter -> post -> parse (таблица — вектор мап)
(fedstat/load-with-filters "31074"
  {:transport transport/default-transport
   :filters {"Субъект РФ" "Приморский край"}})
```

## API

| Неймспейс | Публичные функции | Порт чего |
|---|---|---|
| `fedstat.core` | `get-data-ids`, `load-with-filters` | `fedstat_get_data_ids.R`, `fedstat_data_load_with_filters.R` |
| `fedstat.indicators` | `indicator-info`, `parse-indicators` | `fedstat_indicator_info.R` |
| `fedstat.filters` | `filter-data-ids` | `fedstat_data_ids_filter.R` |
| `fedstat.post` | `post-data-ids`, `build-body`, `url-encode`, `default-headers` | `fedstat_post_data_ids_filtered.R`, `fedstat_config.R` |
| `fedstat.sdmx` | `parse-sdmx-to-table` | `fedstat_parse_sdmx_to_table.R` |
| `fedstat.data-ids` | `build-data-ids`, `object-classes` | сборка data.frame из `fedstat_get_data_ids.R` |
| `fedstat.page` | `csrf-token`, `script-nodes`, `filters-script-index`, `parse-filters-js`, `parse-object-ids-js`, `js-fragment->edn` | `parse_js1.R`, `parse_js2.R`, CSRF-извлечение |
| `fedstat.transport` | `default-transport` | замена `httr` (babashka.curl → curl-бинарь) |

Ключевые контракты:

- **Модель data-ids** — `{:rows [...] :meta {...}}`. Строка: `:filter-field-id`, `:filter-field-title`, `:filter-value-id`, `:filter-value-title`, `:filter-field-object-ids` (line/column/filter-роль поля). `:meta` — аналог атрибутов data.frame в R: `:fedstat/csrf-token`, `:fedstat/csrf-token-name`, `:fedstat/indicator-id`, `:fedstat/base-url`, `:fedstat/cookie-jar`.
- **Транспорт** — инжектируемая fn `[req] -> resp`, `req {:method :get|:post :url :headers :body :content-type :cookie-jar :follow-redirects :timeout-seconds}`, `resp {:status :headers :body}` с `:body` байтами, ключи заголовков в нижнем регистре. Вся HTTP-логика принимает `:transport` опцией — тесты остаются офлайн. Контракт задокументирован в `src/fedstat/post.clj`.
- **Ошибки** — `ex-info` с сообщениями, зеркалящими R-оригинал (302/403/503, CSRF-страница, HTML вместо данных, неуказанные фильтры, несовпавшие значения и т.д.). Предупреждения печатаются в `*err*` с префиксом `WARNING:`, как `warning()` в R.

## Команды

```bash
bb run test       # тест-сьют (офлайн: фикстуры + фейковый транспорт)
bb run live-test  # то же + системное свойство fedstat.test.live=true
                  # (зарезервировано под live-тесты; сейчас ни один тест его не читает)
clojure -M:test   # тот же сьют на JVM
bb run fmt        # cljfmt fix по src/, test/, build.clj
bb run jar        # собрать jar в out/ (tools.build, версия = числу git-ревизий)
bb run repl       # nREPL на 7888 с cider-middleware
```

Тест-раннер — `test/fedstat/test_runner.clj`, намеренно на чистой стандартной библиотеке, чтобы сьют был идентичен в bb и на JVM. Текущий объём: 70 тестов, 163 assert.

Окружение разработки также описано `flake.nix` (`nix develop` → babashka, gnumake; M2_HOME в ./.m2).

## Структура

```
src/fedstat/
  page.clj        # парсинг страницы индикатора (pure)
  data_ids.clj    # сборка {:rows :meta} (pure)
  filters.clj     # фильтрация по заголовкам (pure)
  post.clj        # сборка тела POST + POST + маппинг ошибок
  sdmx.clj        # SDMX GenericData -> таблица (pure)
  indicators.clj  # каталог /organizations/ (pure-парсер + HTTP-обёртка)
  core.clj        # get-data-ids, load-with-filters (оркестрация, ретраи)
  transport.clj   # default-transport на babashka.curl
test/fedstat/*_test.clj   # clojure.test
test/fixtures/            # живые захваты с fedstat.ru (см. ниже)
dev/capture_fixtures.py   # перезахват фикстур (python3 + curl)
build.clj                 # tools.build: jar-lib, clean
```

## Фикстуры

`test/fixtures/` — сырые ответы fedstat.ru, индикатор 31074 (запрос из README R-пакета: Приморский край, январь 2023, два «Виды показателя», все товары):

| Файл | Источник | Контрольные числа |
|---|---|---|
| `page-31074.html` (~0.6 МБ) | GET `/indicator/31074` | 1276 строк data-ids, фильтры в 12-м `<script>` (индекс 11) |
| `sdmx-31074.xml` (~1 МБ) | POST `downloadData.do?format=sdmx` | 1489 строк, 3 codelist'а: s_POK 9, s_grtov 1105, s_OKATO 119 кодов |
| `orgs.html` (~10 МБ) | GET `/organizations/` | 5172 строки после dedup, 34 отдела, 172 hidden |

Фикстуры живые и **дрейфуют вместе с сайтом** (фиксированное значение поля `30611` уже менялось с `1` на `950473` — assert в `data_ids_test.clj` пришлось обновлять). Перезахват: `python3 dev/capture_fixtures.py` (требует сети; POST-часть может не пройти из-за WAF — тогда sdmx-фикстуру обновлять с другого IP).

## Отличия от R-оригинала

Замены зависимостей (причины — в AGENTS.md):

| R (fedstatAPIr) | Здесь |
|---|---|
| `httr` (handle, RETRY) | `babashka.curl` (шеллится в curl-бинарь; cookie-jar/таймауты через `:raw-args`), ретраи — явный цикл |
| `jsonlite` | `cheshire` |
| `xml2` + `readsdmx` | `clojure.data.xml` (встроен в bb) по предобработанному XML |
| атрибуты data.frame | ключи `:fedstat/*` в `:meta` |

Семантические отличия (осознанные):

1. **Имена колонок SDMX-таблицы** — по concept-id из codelist'ов напрямую. Артефакты переименования readsdmx (`X30.ОКАТО` → `30-OKATO`) не воспроизводятся: это костыль readsdmx, а не семантика данных.
2. **Парсер `/organizations/`** — div-tree токенизатор вместо xpath xml2; правила группировки и отбора индикаторов воспроизведены из xpath-логики R и проверены byte-for-byte на фикстуре (5172 строки, в т.ч. квирк R: `div//*[@class=...]` не выбирает i_actual, сидящие прямо под `ved_child`).
3. **JavaScript→JSON трюк** (quote-unquoted-keys) портирован один-в-один, включая построчный fast path с фолбэком на целый текст.

## Происхождение и лицензия

Порт сделан по исходникам `fedstatAPIr` v1.1.0; апстрим — <https://github.com/DenchPokepon/fedstatAPIr>). Оригинал — MIT.
