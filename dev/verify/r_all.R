#!/usr/bin/env Rscript
# R-side parity harness: runs the ORIGINAL fedstatAPIr 1.1.0 against live
# fedstat.ru and local fixtures, dumping artifacts to dev/verify/out/ for
# comparison with the Clojure port (bb_all.clj).
#
# Run from the fedstat-lib directory inside the nix R shell:
#   nix shell --impure --expr '<rWrapper with jsonlite httr xml2 readsdmx data.table magrittr>' \
#     --command bash -c 'R_LIBS=$HOME/.local/lib/R/fedstat-verify Rscript dev/verify/r_all.R'
suppressPackageStartupMessages({
  library(fedstatAPIr)
  library(jsonlite)
})

out <- "dev/verify/out"
dir.create(out, recursive = TRUE, showWarnings = FALSE)

COLS <- c("filter_field_id", "filter_field_title", "filter_value_id",
          "filter_value_title", "filter_field_object_ids")

wl <- function(lines, path) writeLines(lines, path, useBytes = TRUE)

dump_df_natural <- function(df, path) {
  write.table(df[, COLS], path, sep = "\t", quote = FALSE, row.names = FALSE,
              col.names = FALSE, na = "")
}

# ---------------------------------------------------------------------------
# V1: utils::URLencode(reserved = TRUE)
# ---------------------------------------------------------------------------
strs <- readLines("dev/verify/urlencode-strings.txt", warn = FALSE, encoding = "UTF-8")
strs <- strs[nzchar(strs)]
enc <- vapply(strs, function(s) utils::URLencode(s, reserved = TRUE),
              character(1), USE.NAMES = FALSE)
wl(enc, file.path(out, "r-urlencode.txt"))

# ---------------------------------------------------------------------------
# V3: live data_ids via fedstat_get_data_ids, capturing the fetched HTML at
#     httr::RETRY exit so the Clojure side can parse identical bytes.
# ---------------------------------------------------------------------------
page_file <- normalizePath(file.path(out, "page-live.html"), mustWork = FALSE)
trace(httr::RETRY, print = FALSE,
      exit = quote(if (!inherits(resp, "error") && !is.null(resp$content)) {
        writeBin(resp$content, page_file)
      }))
data_ids <- tryCatch(
  fedstat_get_data_ids("31074", timeout_seconds = 60),
  error = function(e) { untrace(httr::RETRY); stop(e) }
)
untrace(httr::RETRY)

dump_df_natural(data_ids, file.path(out, "r-data_ids.tsv"))

tn <- attr(data_ids, "fedstat_csrf_token_name"); if (is.null(tn)) tn <- "NULL"
tk <- attr(data_ids, "fedstat_csrf_token");     if (is.null(tk)) tk <- "NULL"
wl(c(tn, tk,
     attr(data_ids, "fedstat_indicator_id"),
     attr(data_ids, "fedstat_base_url")),
   file.path(out, "r-data_ids-attrs.txt"))

# NOTE: V2 (parse_js/CSRF), V4 (filters) and V5 (POST body) live in
# r_replay.R — they run offline against the artifacts captured here plus the
# neutrally extracted script slices (dump_scripts.py runs in between).

# ---------------------------------------------------------------------------
# V6: fedstat_parse_sdmx_to_table on the fixture
# ---------------------------------------------------------------------------
fmt_df <- function(df) {
  df <- as.data.frame(df)
  df[] <- lapply(df, function(col) {
    if (is.numeric(col)) {
      ifelse(is.na(col), "", sprintf("%.12g", col))
    } else {
      ifelse(is.na(col), "", as.character(col))
    }
  })
  df
}

sdmx_path <- "test/fixtures/sdmx-31074.xml"
raw <- readBin(sdmx_path, what = "raw", n = file.size(sdmx_path))
tab <- fedstat_parse_sdmx_to_table(raw, return_type = "data", try_to_parse_ObsValue = TRUE)
dict <- fedstat_parse_sdmx_to_table(raw, return_type = "dictionary")

tab_f <- fmt_df(tab)
dict_f <- fmt_df(dict)
ord_tab <- do.call(order, as.list(tab_f))
ord_dict <- do.call(order, as.list(dict_f))
write.table(tab_f[ord_tab, ], file.path(out, "r-sdmx-data.tsv"), sep = "\t",
            quote = FALSE, row.names = FALSE, col.names = TRUE, na = "")
write.table(dict_f[ord_dict, ], file.path(out, "r-sdmx-dict.tsv"), sep = "\t",
            quote = FALSE, row.names = FALSE, col.names = TRUE, na = "")
wl(c(paste("rows", nrow(tab)), paste("dict_rows", nrow(dict)),
     paste("cols", paste(names(tab), collapse = ","))),
   file.path(out, "r-sdmx-meta.txt"))

# ---------------------------------------------------------------------------
# V7: fedstat_indicator_info (live) — parser comparison against the same page
#     fetched by the Clojure side with curl.
# ---------------------------------------------------------------------------
info <- fedstat_indicator_info()
info_f <- as.data.frame(info)[, c("department", "group", "id", "title")]
info_f[] <- lapply(info_f, function(col) ifelse(is.na(col), "", as.character(col)))
ord_i <- do.call(order, as.list(info_f))
write.table(info_f[ord_i, ], file.path(out, "r-indicators.tsv"), sep = "\t",
            quote = FALSE, row.names = FALSE, col.names = TRUE, na = "")
wl(paste("rows", nrow(info_f)), file.path(out, "r-indicators-meta.txt"))

cat("R harness done\n")
