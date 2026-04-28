/* The bridge between the app and the page that frames it (docs/index.html).

   It is not build output. It is a hand written classic script that lives beside the built app and is
   pulled in by one tag added to docs/app/index.html after every build, next to the tally-server.js
   tag. See docs/NOTES.md for the exact two lines to put back.

   Standalone, this file does nothing at all: every line below is behind the check that the app is
   inside a frame. Embedded, it does three things.

   1. It marks the document with data-embedded. web/src/styles.css keys two rules off that attribute
      and is otherwise untouched, so the app on its own is unchanged.
   2. It measures the app's own content height and hands it to the page, which makes the frame that
      tall. A frame the height of its content has nothing left to scroll, which is the whole point:
      the reader scrolls the page, never a small window inside it. A ResizeObserver and a
      MutationObserver keep it reported, so a longer statement list or a new row grows the frame.
   3. It listens for the page saying how far the frame's bottom edge sits below the reader's window,
      and puts that on a custom property the toast shelf lifts itself by. Without it a toast would be
      fixed to the bottom of a frame that is now taller than the screen, which is nowhere the reader
      is looking. */

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
    // data-embedded drops the app's min-height: 100vh, so these all measure real content rather than
    // the frame we are about to size. The rect covers the case where a top margin collapses out of
    // the body and scrollHeight alone reads short.
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

  // A short timer rather than requestAnimationFrame: frames that are scrolled out of view can have
  // their animation frames throttled, and the first height has to arrive whether or not the reader
  // has got down to the panel yet.
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
      // The observer watches the body only, so setting the custom property on the root element below
      // cannot feed back into it.
      new MutationObserver(schedule).observe(document.body, {
        childList: true,
        subtree: true,
        characterData: true,
        attributes: true
      });
    }

    window.addEventListener("resize", schedule);
    window.addEventListener("load", schedule);
    // Panels arrive on a short entrance animation; these two catch the settled height.
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
