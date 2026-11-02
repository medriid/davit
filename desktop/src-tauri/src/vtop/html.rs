//! What every parser shares: jsoup's small conveniences over `scraper`.

use regex::Regex;
use scraper::{ElementRef, Html, Selector};
use std::sync::OnceLock;

pub fn sel(s: &str) -> Selector {
    Selector::parse(s).unwrap_or_else(|e| panic!("bad selector {s}: {e:?}"))
}

pub fn re(s: &str) -> Regex {
    Regex::new(s).unwrap_or_else(|e| panic!("bad regex {s}: {e}"))
}

/// Collapses runs of whitespace (non-breaking spaces too) and trims.
pub fn clean(s: &str) -> String {
    static SPACE: OnceLock<Regex> = OnceLock::new();
    SPACE.get_or_init(|| re(r"[\s\u{00a0}]+")).replace_all(s, " ").trim().to_string()
}

pub fn number(s: &str) -> Option<f64> {
    clean(s).replace(',', "").replace('₹', "").trim().parse().ok().filter(|n: &f64| n.is_finite())
}

const BLOCK: &[&str] = &[
    "address", "article", "aside", "blockquote", "br", "center", "dd", "details", "div", "dl", "dt", "fieldset",
    "figcaption", "figure", "footer", "form", "h1", "h2", "h3", "h4", "h5", "h6", "header", "hr", "li", "main",
    "nav", "ol", "option", "p", "pre", "section", "select", "summary", "table", "tbody", "td", "tfoot", "th",
    "thead", "tr", "ul",
];

fn walk(el: ElementRef, skip: &[&str], out: &mut String) {
    for child in el.children() {
        if let Some(t) = child.value().as_text() {
            out.push_str(t);
        } else if let Some(e) = ElementRef::wrap(child) {
            let name = e.value().name();
            if matches!(name, "script" | "style" | "noscript") || skip.iter().any(|c| e.value().has_class(c, scraper::CaseSensitivity::AsciiCaseInsensitive) || *c == name) {
                continue;
            }
            let block = BLOCK.contains(&name);
            if block {
                out.push(' ');
            }
            walk(e, skip, out);
            if block {
                out.push(' ');
            }
        }
    }
}

/// An element's visible text, spaced as jsoup's `text()`: block elements separate, inline ones don't.
pub fn text(el: ElementRef) -> String {
    let mut out = String::new();
    walk(el, &[], &mut out);
    clean(&out)
}

/// [text] leaving out the elements of these classes or tag names.
pub fn text_without(el: ElementRef, skip: &[&str]) -> String {
    let mut out = String::new();
    walk(el, skip, &mut out);
    clean(&out)
}

pub fn doc_text(doc: &Html) -> String {
    text(doc.root_element())
}

pub fn attr<'a>(el: ElementRef<'a>, name: &str) -> &'a str {
    el.value().attr(name).unwrap_or("")
}

pub fn first<'a>(el: ElementRef<'a>, s: &str) -> Option<ElementRef<'a>> {
    el.select(&sel(s)).next()
}

pub fn all<'a>(el: ElementRef<'a>, s: &str) -> Vec<ElementRef<'a>> {
    el.select(&sel(s)).collect()
}

pub fn dfirst<'a>(doc: &'a Html, s: &str) -> Option<ElementRef<'a>> {
    doc.select(&sel(s)).next()
}

pub fn dall<'a>(doc: &'a Html, s: &str) -> Vec<ElementRef<'a>> {
    doc.select(&sel(s)).collect()
}

/// An element's direct `th`/`td` children.
pub fn direct_cells(tr: ElementRef) -> Vec<ElementRef> {
    tr.children().filter_map(ElementRef::wrap).filter(|e| matches!(e.value().name(), "td" | "th")).collect()
}

pub fn direct_tds(tr: ElementRef) -> Vec<ElementRef> {
    tr.children().filter_map(ElementRef::wrap).filter(|e| e.value().name() == "td").collect()
}

pub fn closest<'a>(el: ElementRef<'a>, name: &str) -> Option<ElementRef<'a>> {
    let mut at = Some(el);
    while let Some(e) = at {
        if e.value().name() == name {
            return Some(e);
        }
        at = e.parent().and_then(ElementRef::wrap);
    }
    None
}

/// A table read by its header text rather than by position.
pub struct Table<'a> {
    pub headers: Vec<String>,
    pub rows: Vec<Vec<String>>,
    pub elements: Vec<ElementRef<'a>>,
}

impl Table<'_> {
    /// The first column whose header contains any of the words, ignoring case.
    pub fn find(&self, words: &[&str]) -> Option<usize> {
        self.headers.iter().position(|h| {
            let h = h.to_lowercase();
            words.iter().any(|w| h.contains(&w.to_lowercase()))
        })
    }

    pub fn value(&self, row: usize, column: Option<usize>) -> String {
        column.and_then(|c| self.rows[row].get(c)).cloned().unwrap_or_default()
    }
}

pub fn cells(tr: ElementRef) -> Vec<String> {
    direct_cells(tr).into_iter().map(text).collect()
}

/// The first row with only `th` cells, or the first row, is the header; empty rows are dropped.
pub fn table(t: ElementRef) -> Table {
    let trs: Vec<_> = all(t, "tr").into_iter().filter(|tr| closest_parent_table(*tr).map(|p| p.id()) == Some(t.id())).collect();
    if trs.is_empty() {
        return Table { headers: vec![], rows: vec![], elements: vec![] };
    }
    let has = |tr: &ElementRef, name: &str| tr.children().filter_map(ElementRef::wrap).any(|c| c.value().name() == name);
    let head = trs.iter().find(|tr| has(tr, "th") && !has(tr, "td")).copied().unwrap_or(trs[0]);
    let body: Vec<_> = trs.into_iter().filter(|tr| tr.id() != head.id() && cells(*tr).iter().any(|c| !c.is_empty())).collect();
    Table { headers: cells(head), rows: body.iter().map(|tr| cells(*tr)).collect(), elements: body }
}

fn closest_parent_table(tr: ElementRef) -> Option<ElementRef> {
    tr.parent().and_then(ElementRef::wrap).and_then(|p| closest(p, "table"))
}

/// `label | value` rows, in order. Rows without a value are skipped.
pub fn label_values(t: ElementRef) -> Vec<(String, String)> {
    all(t, "tr")
        .into_iter()
        .filter_map(|tr| {
            let c: Vec<_> = cells(tr).into_iter().filter(|c| !c.is_empty()).collect();
            if c.len() < 2 {
                None
            } else {
                Some((c[0].trim_end_matches([':', ' ']).to_string(), c[1].clone()))
            }
        })
        .collect()
}
