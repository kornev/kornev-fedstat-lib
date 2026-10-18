#!/usr/bin/env Rscript
# R-side replay harness (offline): V2 parse_js/CSRF on the captured page,
# V4 filter scenarios, V5 POST body capture. Rebuilds data_ids from the
# r_all.R dumps so both sides consume identical input.
#
# Run after r_all.R and dump_scripts.py:
#   python3 dev/verify/dump_scripts.py
#   nix shell ... --command bash -c 'R_LIBS=... Rscript dev/verify/r_replay.R'
suppressPackageStartupMessages({
  library(fedstatAPIr)
  library(jsonlite)
})

out <- "dev/verify/out"
COLS <- c("filter_field_id", "filter_field_title", "filter_value_id",
          "filter_value_title", "filter_field_object_ids")
wl <- function(lines, path) writeLines(lines, path, useBytes = TRUE)
page_file <- normalizePath(file.path(out, "page-live.html"), mustWork = TRUE)

# --- Rebuild data_ids from the dump, attaching captured attributes ---------
data_ids <- read.delim(file.path(out, "r-data_ids.tsv"), sep = "\t",
                       quote = "", header = FALSE, col.names = COLS,
                       stringsAsFactors = FALSE, encoding = "UTF-8", fileEncoding = "UTF-8")
attrs <- readLines(file.path(out, "r-data_ids-attrs.txt"), encoding = "UTF-8")
attr(data_ids, "fedstat_csrf_token_name") <- attrs[[1]]
attr(data_ids, "fedstat_csrf_token")      <- attrs[[2]]
attr(data_ids, "fedstat_indicator_id")    <- attrs[[3]]
attr(data_ids, "fedstat_base_url")        <- attrs[[4]]
# fake handle: without it fedstat_post_data_ids_filtered silently re-fetches
# a fresh CSRF token (its handle-or-token null check) and the captured body
# becomes uncomparable with the Clojure side, which reuses the dump's token
attr(data_ids, "fedstat_handle")          <- httr::handle(attrs[[4]])

# ---------------------------------------------------------------------------
# V2: parse_js1 / parse_js2 on the neutrally extracted script slices + CSRF
#     via the original xml2 xpath block.
# ---------------------------------------------------------------------------
scripts <- list.files(file.path(out, "scripts"), full.names = TRUE)
idx <- which(vapply(scripts, function(f) {
  any(grepl("filters: \\{", readLines(f, warn = FALSE, encoding = "UTF-8")))
}, logical(1)))
stopifnot(length(idx) == 1)
lines <- readLines(scripts[[idx]], warn = FALSE, encoding = "UTF-8")

cast_chr <- function(x) {
  if (is.list(x)) lapply(x, cast_chr) else as.character(x)
}
pj1 <- fedstatAPIr:::parse_js1(lines)
pj2 <- fedstatAPIr:::parse_js2(lines)
wl(toJSON(cast_chr(pj1), auto_unbox = TRUE, pretty = TRUE),
   file.path(out, "r-filters.json"))
wl(toJSON(cast_chr(pj2), auto_unbox = TRUE, pretty = TRUE),
   file.path(out, "r-objects.json"))

html <- xml2::read_html(page_file, encoding = "UTF-8")
token_holder <- xml2::xml_find_all(html, ".//div[@id='downloadTokenHolder']")
holder <- token_holder[[1]]
tni <- xml2::xml_find_first(holder, ".//input[@name='struts.token.name']")
csrf_name <- xml2::xml_attr(tni, "value")
ti <- xml2::xml_find_first(holder, paste0(".//input[@name='", csrf_name, "']"))
csrf_val <- xml2::xml_attr(ti, "value")
wl(c(csrf_name, csrf_val), file.path(out, "r-csrf.txt"))

# ---------------------------------------------------------------------------
# V4: fedstat_data_ids_filter over shared scenarios
# ---------------------------------------------------------------------------
scen <- fromJSON("dev/verify/scenarios.json", simplifyVector = FALSE)
for (s in scen) {
  sid <- s$id
  flt <- lapply(s$filters, function(p) p[[2]])
  names(flt) <- vapply(s$filters, function(p) p[[1]], character(1))

  warns <- character(0)
  res <- tryCatch(
    withCallingHandlers(
      fedstat_data_ids_filter(data_ids, filters = flt, disable_warnings = FALSE),
      warning = function(w) {
        warns <<- c(warns, conditionMessage(w))
        invokeRestart("muffleWarning")
      }
    ),
    error = function(e) e
  )

  if (inherits(res, "error")) {
    wl(c("ERROR", conditionMessage(res)), file.path(out, paste0("r-filters-", sid, ".msg")))
  } else {
    wl("OK", file.path(out, paste0("r-filters-", sid, ".msg")))
    write.table(res[, COLS], file.path(out, paste0("r-filters-", sid, ".tsv")),
                sep = "\t", quote = FALSE, row.names = FALSE,
                col.names = FALSE, na = "")
  }
  wl(if (length(warns)) paste0("WARNING: ", warns) else character(0),
     file.path(out, paste0("r-filters-", sid, ".warn")))
}

# ---------------------------------------------------------------------------
# V5: POST body via trace(httr::POST) entry dump (original assembly code).
#     The POST itself is expected to be WAF-rejected; the body is verified.
# ---------------------------------------------------------------------------
body_scen <- Filter(function(s) s$id == "readme", scen)[[1]]
body_flt <- lapply(body_scen$filters, function(p) p[[2]])
names(body_flt) <- vapply(body_scen$filters, function(p) p[[1]], character(1))
flt_ids <- fedstat_data_ids_filter(data_ids, filters = body_flt)

trace(httr::POST, print = FALSE, quote({
  wl(url, file.path(out, "r-post-url.txt"))
  wl(body, file.path(out, "r-body.txt"))
}))
post_err <- tryCatch({
  fedstat_post_data_ids_filtered(flt_ids, data_format = "sdmx", timeout_seconds = 60)
  NULL
}, error = function(e) e)
untrace(httr::POST)
wl(if (is.null(post_err)) "OK" else paste0("ERROR: ", conditionMessage(post_err)),
   file.path(out, "r-post-result.txt"))

cat("R replay done\n")
