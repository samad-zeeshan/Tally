/* The bridge between the app and the page that frames it (docs/index.html).

   It reports this document's height so the page can make the frame that tall, leaving nothing to scroll
   inside it, and takes back how far the frame's bottom edge falls below the reader's window so the toast
   shelf can lift itself into view. Outside a frame every line is skipped, so the app alone is unchanged.

   Not build output. The tag that pulls it in has to be put back into docs/app/index.html after every
   build, see docs/NOTES.md. */

(function () {
  "use strict";

  if (window.parent === window) {
    return;
  }

  var root = document.documentElement;
  root.setAttribute("data-embedded", "");

  var reported = -1;
  var pending = 0;

  function contentHeight() {
    var body = document.body;
    if (!body) {
      return 0;
    }
    // data-embedded drops the app's min-height: 100vh, so this measures real content and not the frame
    // we are about to size. The rect covers a top margin collapsing out, where scrollHeight reads short.
    var box = body.getBoundingClientRect();
    return Math.ceil(Math.max(root.scrollHeight, body.scrollHeight, box.bottom + (window.scrollY || 0)));
  }

  function report() {
    pending = 0;
    var height = contentHeight();
    if (height > 0 && Math.abs(height - reported) >= 1) {
      reported = height;
      window.parent.postMessage({ tally: "height", height: height }, "*");
    }
  }

  // A timer, not requestAnimationFrame: a frame scrolled out of view can have its animation frames
  // throttled, and the first height has to arrive before the reader gets down to the panel.
  function schedule() {
    if (pending) {
      return;
    }
    pending = window.setTimeout(report, 50);
  }

  function watch() {
    schedule();

    if (typeof ResizeObserver === "function") {
      new ResizeObserver(schedule).observe(document.body);
    }
    if (typeof MutationObserver === "function") {
      // The body only. Watching the root would see the custom property set below and loop on itself.
      new MutationObserver(schedule).observe(document.body, {
        childList: true,
        subtree: true,
        characterData: true,
        attributes: true
      });
    }

    window.addEventListener("resize", schedule);
    window.addEventListener("load", schedule);
    // Panels arrive on an entrance animation, so these two catch the settled height.
    window.setTimeout(schedule, 350);
    window.setTimeout(schedule, 1400);
  }

  window.addEventListener("message", function (event) {
    if (event.source !== window.parent) {
      return;
    }
    var data = event.data;
    if (!data || data.tally !== "view" || typeof data.lift !== "number") {
      return;
    }
    root.style.setProperty("--embed-lift", Math.round(data.lift) + "px");
  });

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", watch);
  } else {
    watch();
  }
})();
