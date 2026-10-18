# AGENTS.md — fedstat

## Что это

Clojure/babashka-порт R-пакета fedstatAPIr (ЕМИСС/fedstat.ru): `get-data-ids` → `filter-data-ids` → `post-data-ids` → `parse-sdmx-to-table`. Пользовательская документация — в `README.md`, здесь — правила разработки и эмпирические находки. **Не повторять выводимое ниже ошибками.**

## Команды

```bash
bb run test        # офлайн-сьют (фикстуры + фейковый транспорт) — основной gate
clojure -M:test    # тот же сьют на JVM — прогонять тоже, код двухплатформенный
bb run live-test   # сьют + -Dfedstat.test.live=true (свойство зарезервировано,
                   # ни один тест его пока не читает — live-проверки делать вручную через REPL)
bb run fmt         # cljfmt fix (src, test, build.clj)
bb run jar         # out/*.jar через tools.build
```

Порядок работы: правка → `bb run fmt` → оба раннера зелёные → только тогда готово. Тест-раннер намеренно на чистой stdlib (`test/fedstat/test_runner.clj`), чтобы bb и JVM гоняли идентичный сьют.

## Правила разработки

1. **TDD**: сначала тест (красный), потом реализация. Тесты — `clojure.test`, фикстуры из `test/fixtures/`, HTTP — фейковый транспорт (fn req→resp), сеть в тестах запрещена.
2. **Транспорт всегда инжектируется**: публичные HTTP-функции принимают `{:transport fn}` и не делают сеть сами, кроме `fedstat.transport/default-transport`. Контракт: `req {:method :get|:post :url :headers :body :content-type :cookie-jar :follow-redirects :timeout-seconds}` → `{:status :headers :body}` (body — байты, ключи заголовков в нижнем регистре). Нарушение контракта = баг.
3. **Паритет с R там, где заявлен**: семантика фильтров, порядок полей тела POST, тексты ошибок (`ex-info`, близко к оригиналу), предупреждения — `(binding [*out* *err*] (println "WARNING: ..."))` как `warning()` в R. Улучшать тексты можно, но осознанно и с обновлением тестов.
4. **Чистые функции** в `page`, `data-ids`, `filters`, `sdmx` и парсере `indicators` — без I/O; I/O только в `core`, `post`, `transport`.
5. **Двухплатформенность**: весь код должен грузиться и в bb ≥ 1.13, и на JVM (clojure 1.12.6). bb-встроенные либы (cheshire, data.xml, babashka.curl) при необходимости дублируются в `deps.edn` для JVM — версии там уже согласованы.
6. Новый R-файл портируется как новый неймспейс + строка в таблицу README; меняешь поведение — обновляй README/AGENTS.

## Модель данных и байт-уровневые факты (не выводить заново)

- **data-ids** — `{:rows [...] :meta {...}}`. Строка: `:filter-field-id/-title`, `:filter-value-id/-title`, `:filter-field-object-ids`. `:meta` — аналог атрибутов data.frame: `:fedstat/csrf-token`, `:fedstat/csrf-token-name`, `:fedstat/indicator-id`, `:fedstat/base-url`, `:fedstat/cookie-jar`. Ошибки сборки: пустые значения поля («fedstat returned erroneous data_ids... lagging»), неуникальные пары field/value.
- **CSRF**: токен в `<div id="downloadTokenHolder">` — input `struts.token.name` даёт имя параметра, input с этим именем — значение. Токены **одноразовые**: повторный POST со старым токеном = reject; повтор использования детектируется по CSRF-странице в ответе. `post-data-ids` при отсутствии токена в meta сам делает GET за свежим (warning в `*err*`).
- **Тело POST** (`build-body`, порядок полей важен, порт R): `title=<url-encode>`, `struts.token.name`, `<token-name>=<token>`, `id`, затем все `lineObjectIds`, `columnObjectIds`, `selectedFilterIds=<fid>_<vid>` по строкам, `filterObjectIds`. Индикатор — строка с `:filter-field-id "0"`.
- **url-encode** = `utils::URLencode(reserved=TRUE)`: проходят только `[A-Za-z0-9]`, остальные **байты UTF-8** → `%XX` (кириллица — мультибайт, пробел — `%20`).
- **JS→JSON трюк** (порт parse_js1/2): regex `\b(?=([^']*'[^']*')*[^']*$)` вставляет кавычку на границах слов вне одинарных кавычек, потом `'`→`"`, парсинг cheshire. Fast path — построчно, только если в каждой строке чётное число `'` (строки не пересекают строки), иначе целый текст. `parse-filters-js` режет от `filters: {` до `left_columns: [`; `parse-object-ids-js` — от `left_columns: [` до `grid.init();` с rename `left_columns/groups/filterObjectIds → lineObjectIds`, `top_columns → columnObjectIds`.
- **object-classes**: js `filterObjectIds` уже переименованы парсером в lineObjectIds; если ни один id ≠ "0", синтетически добавляется `["filterObjectIds" ["0"]]`. Класс поля — первое вхождение.
- **Фильтры** (`filter-data-ids`): нормализация обеих сторон (trim, `\s+`→" ", lowercase); `"*"` = все значения и **не может** стоять в векторе длиной >1; неизвестные заголовки полей → ошибка с их перечнем; неуказанные многозначные поля → ошибка со списком и подсказкой `"*"`; однозначные поля и «Показатель» (fid "0") добавляются автоматически; несовпавшие значения — warning (съём через `:disable-warnings?`); фильтр, давший 0 строк → ошибка.
- **SDMX** (`parse-sdmx-to-table`): до парсинга XML режутся префиксы тегов (`<generic:Series>` → `<Series>`) и xmlns/xsi/xml-атрибуты regex'ами — data.xml так проще. **Атрибуты в data.xml — keyword-ключи**: `(get (:attrs e) :id)`, не строки. `:data` → вектор мап: колонки по concept-id с title'ами из codelist'ов + `<concept>_code` сырыми id + Time/ObsValue/атрибуты; `:dictionary` → `[{:field-id :field-title :value-id :value-title}]`. Пустое значение любого из четырёх ключей словаря → «NA in lookup sdmx table». Concept codelist'а, отсутствующий в данных → ошибка «Expected columns not found». ObsValue: `,`→`.`, blank → nil, непарсящееся → throw с советом `:try-to-parse-obs-value? false`. Ответ с `<html`/CSRF — отдельные ошибки. Не-string/nil вход — warning «data_raw is not raw» и nil (как в R).
- **/organizations/** (`parse-indicators`): div-tree токенизатор (regex-скан `<div\b[^>]*>|</div>`, стек вектором — `conj` на список prepend'ит, бесконечный цикл; скобки считать!). Отдел/группа/индикаторы — как xpath R: items = прямые div-дети `#orgsTree`; группы = дети первого `div.ved_child` item'а; title группы = первый span `i_name org|ci` в `div.ved_pl dtable group`; **индикаторы = i_actual div'ы глубины ≥ 2 под group ved_child** (xpath `'div//*[@class="ved_item group i_actual"]'` ищет только НИЖЕ детей ved_child — i_actual, сидящие прямо под ved_child, НЕ выбираются; из-за этого 18 ведомств с «плоской» вёрсткой в выдаче R отсутствуют, пустых group в выгрузке нет). `<span class="i_name">` может иметь другие атрибуты (style у hidden) — матчить по префиксу класса, не точному тегу. hidden (`href="#"`) перенумеровываются `#1 #2…` глобально по порядку появления (нумерация по порядку документа, до сортировки); dedup по `[department group id]`, первое вхождение wins (`volatile!` + set, не array-map — переполнение после 8 записей). Паритет проверен byte-for-byte против живого R на фикстуре: 5172 строки / 34 отдела / 172 hidden.
- **Ретраи** (`core`): GET — до `:retry-max-times` (default 3) попыток, паузы 1,2,4…c, cap 60 (паритет `httr::RETRY`); POST внутри `post-data-ids` **не ретраится** (токены одноразовые); `load-with-filters` ретраит весь цикл, на каждом ретрае свежий CSRF. Процессные фейлы транспорта (нет :status) = бросок ex-info = retryable.

## Транспорт и TLS

- `default-transport` — `babashka.curl`. Опций `:cookie-jar`/`:max-time` у curl-пода нет → всё через `:raw-args`; `--cookie` и `--cookie-jar` **одним файлом** (сессия GET→POST, аналог `httr::handle`); `:throw false`, `:as :bytes`. В bb библиотека встроена, для JVM — деп `babashka/babashka.curl 0.1.2`.
- **Не заменять транспорт на JVM-клиенты** (`babashka.http-client`, clj-http-lite, http-kit): в bb native-image truststore урезан и отстаёт от Mozilla, на ряде хостов — PKIX-фейлы; curl-бинарь ходит через системный CA store и TLS проходит «из коробки». Это причина выбора curl, а не эстетика.

## Задепинованные зависимости и их подводные камни

- `org.clojure/data.xml 0.2.0-alpha9` — **не поднимать на 0.2.0-beta12**: её нет в Maven Central (JVM-ран упадёт на резолве), а bb всё равно тащит встроенную.
- `cheshire 6.2.0`, `babashka/babashka.curl 0.1.2` — нужны только для JVM-рана.
- `fipp 0.6.29` — сейчас **нигде не используется** (кандидат на удаление из deps.edn).
- `.gitignore` игнорит **все dotfiles** кроме белого списка — конфиги новых тулов (напр. `.clj-kondo/config.edn`) в коммит не попадут, пока их явно не разбелилисть. `.cpcache`, `.lsp` — локальный мусор, в git не идут.

## Фикстуры

`test/fixtures/` — живые захваты (см. таблицу в README; контрольные числа зафиксированы в тестах: 1276 data-id строк, 1489 SDMX-строк / 3 codelist'а, 17525 строк каталога после dedup). Фикстуры **дрейфуют вместе с сайтом** — при перезахвате (`python3 dev/capture_fixtures.py`) сначала прогнать сьют и обновить задепинованные числа/ассерты, если сайт изменил данные. POST-часть capture-скрипта может не пройти с заблокированного WAF IP — тогда sdmx-фикстуру обновлять из другой сети.

## Live-статус (fedstat.ru)

- 2026-10-16: **полный цикл работает живьём** (пример `dev/examples/example_broad_money.clj`: каталог → get-data-ids → POST downloadData.do → parse, индикатор 62278, 11 лет данных).
- 2026-10-14: GET `/indicator/<id>` и `/organizations/` — работали с честным UA из `default-headers`, cookie-jar получал `session-cookie` + `JSESSIONID`.
- 2026-10-14: POST `downloadData.do` отклонялся WAF (302 → `/indicator/<id>.do`, после серии попыток — 403 «Request ID»). Проверено: байт-в-байт тела по образцу R и сайтового `FGrid.js downloadFile`, ± Referer/Origin, %20/+, честный/браузерный UA — это серверный антибот, формой запроса не лечится. К 2026-10-16 блокировка сошла (либо IP, либо поведение WAF) — POST прошёл с дефолтными заголовками. Диагностика библиотеки корректна; при повторной блокаде не «чинить» код — разблокировка вопрос IP/сети или поведения WAF, R-пакет вёл бы себя так же.

## Открытые вопросы

- LICENSE при выделении в отдельный репозиторий (MIT, со ссылкой на fedstatAPIr).
- Удалить неиспользуемый fipp.
- Автоматизированные live-тесты: зачитка `fedstat.test.live` в `bb run live-test` заложена, тестов-консьюмеров нет.
