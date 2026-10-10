/* Plays back the recorded runs in data/ as pages of a ledger. Every figure shown is read from those files or summed from them. */
(function () {
  "use strict";

  var data = {};
  var live = { timers: [], frames: [], springs: [], offs: [] };
  var reduce = window.matchMedia("(prefers-reduced-motion: reduce)");
  var $ = function (id) { return document.getElementById(id); };

  function money(minor) {
    var sign = minor < 0 ? "-" : "";
    var v = Math.abs(Math.round(minor));
    return sign + Math.floor(v / 100).toLocaleString("en-US") + "." + String(v % 100).padStart(2, "0");
  }
  function count(n) { return Math.round(n).toLocaleString("en-US"); }

  function el(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text !== undefined) e.textContent = text;
    return e;
  }

  // ?speed=3 plays every run three times faster. The screen recorder uses it to fit each run into a few
  // seconds. Visitors never see the parameter, so the default pace stays the one the page was designed at.
  var speed = Math.min(10, Math.max(0.25, parseFloat(new URLSearchParams(location.search).get("speed")) || 1));
  function later(ms, fn) { live.timers.push(setTimeout(fn, ms / speed)); }
  function frame(fn) { var id = requestAnimationFrame(fn); live.frames = [id]; return id; }

  // A critically damped spring. Retargeting keeps the current value and velocity, so a counter
  // that is still moving when a new entry lands bends toward the new total instead of restarting.
  function Spring(value, render, response) {
    var s = { x: value, v: 0, target: value, raf: 0, last: 0 };
    var omega = 2 * Math.PI / (response || 0.55);
    function step(now) {
      // Small fixed substeps keep the integration stable when frames arrive late or the tab was throttled.
      var left = Math.min((now - s.last) / 1000, 1);
      s.last = now;
      while (left > 0) {
        var dt = Math.min(left, 1 / 120);
        s.v += (-omega * omega * (s.x - s.target) - 2 * omega * s.v) * dt;
        s.x += s.v * dt;
        left -= dt;
      }
      if (Math.abs(s.x - s.target) < 0.5 && Math.abs(s.v) < 5) { s.x = s.target; s.v = 0; s.raf = 0; render(s.x); return; }
      render(s.x);
      s.raf = requestAnimationFrame(step);
    }
    s.to = function (target, instant) {
      s.target = target;
      if (instant || reduce.matches) {
        cancelAnimationFrame(s.raf); s.raf = 0; s.x = target; s.v = 0; render(s.x); return;
      }
      if (!s.raf) { s.last = performance.now(); s.raf = requestAnimationFrame(step); }
    };
    s.stop = function () { cancelAnimationFrame(s.raf); s.raf = 0; };
    live.springs.push(s);
    render(value);
    return s;
  }

  // Entries are written left to right like a pen stroke. With reduced motion they just appear.
  function ink(node, delay) {
    if (!node.animate) return;
    if (reduce.matches) {
      node.animate([{ opacity: 0 }, { opacity: 1 }], { duration: 180, delay: delay || 0, fill: "backwards" });
      return;
    }
    node.animate([{ clipPath: "inset(0 100% 0 0)" }, { clipPath: "inset(0 0% 0 0)" }],
      { duration: 620, delay: delay || 0, easing: "cubic-bezier(.23,1,.32,1)", fill: "backwards" });
  }

  function caption(text) {
    var c = $("caption");
    if (reduce.matches) { c.textContent = text; return; }
    c.classList.add("swap");
    later(170, function () { c.textContent = text; c.classList.remove("swap"); });
  }

  function reset() {
    live.timers.forEach(clearTimeout);
    live.frames.forEach(cancelAnimationFrame);
    live.springs.forEach(function (s) { s.stop(); });
    live.offs.forEach(function (off) { off(); });
    live = { timers: [], frames: [], springs: [], offs: [] };
    $("visual").innerHTML = ""; $("visual").className = "page";
    $("figures").innerHTML = ""; $("wire").innerHTML = "";
    $("caption").classList.remove("swap");
  }

  function wire(step) {
    var li = el("li");
    if (step.note) {
      li.appendChild(el("span", "lost", "Lost. "));
      li.appendChild(document.createTextNode(step.note));
    } else {
      var r = step.request, s = step.response;
      var head = r.method + " " + r.path + (r.headers["Idempotency-Key"] ? "  Idempotency-Key: " + r.headers["Idempotency-Key"] : "");
      li.appendChild(el("code", null, head));
      li.appendChild(document.createTextNode("  returned " + s.status + (s.headers["idempotency-replayed"] ? ", Idempotency-Replayed: true" : "")));
      if (s.body) li.appendChild(el("div")).appendChild(el("code", null, JSON.stringify(s.body)));
    }
    $("wire").appendChild(li);
  }

  function step(run, label) {
    return run.steps.filter(function (s) { return s.label === label; })[0];
  }

  // One running figure in the side column. Numbers move on a spring so a change reads as a change.
  function figure(label, value, fmt, sub) {
    var box = el("div", "figure");
    box.appendChild(el("div", "label", label));
    var out = el("div", "value");
    box.appendChild(out);
    var subEl = el("div", "sub", sub || "");
    box.appendChild(subEl);
    $("figures").appendChild(box);
    var spring = Spring(value, function (x) {
      out.textContent = fmt(x);
      out.classList.toggle("neg", Math.round(x) < 0);
    });
    return { to: spring.to, sub: function (t) { subEl.textContent = t; }, value: out };
  }

  function row(cells, cls) {
    var r = el("div", "row" + (cls ? " " + cls : ""));
    r.appendChild(el("span", "ref", cells[0] || ""));
    r.appendChild(el("span", "part", cells[1] || ""));
    for (var i = 2; i < cells.length; i++) r.appendChild(el("span", "fig", cells[i] === undefined ? "" : cells[i]));
    return r;
  }

  // The ledger page for the first three runs: column heads, entries, and totals that must agree.
  function ledger() {
    var page = $("visual");
    page.appendChild(row(["Ref", "Details", "Debit", "Credit"], "head"));
    var body = el("div", "entries");
    page.appendChild(body);
    var totals = el("div", "totals");
    var tRow = row(["", "Totals", "", ""]), dRow = row(["", "Difference", "", ""], "diff");
    totals.appendChild(tRow); totals.appendChild(dRow);
    page.appendChild(totals);

    var dr = 0, cr = 0, drOut = tRow.children[2], crOut = tRow.children[3], diffOut = dRow.children[3];
    var shown = { dr: 0, cr: 0 };
    function paint() { diffOut.textContent = money(Math.abs(shown.dr - shown.cr)); }
    // Both totals share one spring response and move to their targets together, so the
    // difference stays at 0.00 the whole way through, which is the point being shown.
    var drS = Spring(0, function (x) { shown.dr = Math.round(x); drOut.textContent = money(x); paint(); });
    var crS = Spring(0, function (x) { shown.cr = Math.round(x); crOut.textContent = money(x); paint(); });

    return {
      body: body,
      post: function (entry, opts) {
        opts = opts || {};
        var rows = entry.legs.map(function (leg, i) {
          var r = row([i === 0 ? entry.ref : "", leg.text, leg.dr ? money(leg.dr) : "", leg.cr ? money(leg.cr) : ""],
            opts.cls || "");
          r.dataset.acct = leg.acct;
          body.appendChild(r);
          if (!opts.instant) ink(r, i * 200);
          return r;
        });
        if (!opts.ghost) {
          entry.legs.forEach(function (leg) { dr += leg.dr || 0; cr += leg.cr || 0; });
          drS.to(dr, opts.instant); crS.to(cr, opts.instant);
        }
        return rows;
      }
    };
  }

  // The postings the first three runs write, built from the recorded requests.
  function entries() {
    var run = data.ledger, t = step(run, "transfer"), again = step(run, "retry with the same key");
    function opening(label) {
      var b = step(run, label).request.body;
      return { ref: "open", wire: step(run, label), legs: [
        { acct: "World", text: "World, opens " + b.name, dr: b.openingBalanceMinor },
        { acct: b.name, text: b.name + ", opening", cr: b.openingBalanceMinor }] };
    }
    function transfer(s) {
      var amount = s.request.body.amountMinor, key = s.request.headers["Idempotency-Key"];
      return { ref: key.slice(-4), key: key, amount: amount, wire: s, legs: [
        { acct: "Amara", text: "Amara, to Ben", dr: amount },
        { acct: "Ben", text: "Ben, from Amara", cr: amount }] };
    }
    return [opening("open Amara"), opening("open Ben"), transfer(t), transfer(again)];
  }

  function balances() {
    var b = { World: figure("World", 0, money, "The bank's own account. It goes below zero by exactly what it hands out, so the books still balance."), Amara: figure("Amara", 0, money), Ben: figure("Ben", 0, money) };
    var held = { World: 0, Amara: 0, Ben: 0 };
    b.apply = function (entry, instant) {
      entry.legs.forEach(function (leg) { held[leg.acct] += (leg.cr || 0) - (leg.dr || 0); });
      Object.keys(held).forEach(function (k) { b[k].to(held[k], instant); });
    };
    return b;
  }

  var runs = {
    transfer: function () {
      var list = entries(), book = ledger(), bal = balances();
      caption("Amara and Ben open accounts. The opening money comes out of the bank's own account, called World, so even a new deposit is two lines that agree.");
      [0, 1].forEach(function (i) {
        later(400 + i * 1100, function () { book.post(list[i]); bal.apply(list[i]); wire(list[i].wire); });
      });
      later(3300, function () {
        var t = list[2];
        caption("Amara sends Ben " + money(t.amount) + ". One transfer, two lines: " + money(t.amount) + " out of Amara, " + money(t.amount) + " into Ben. The totals move together and the difference stays at zero, as it always should.");
        book.post(t); bal.apply(t);
        wire(t.wire); wire(step(data.ledger, "balances after the transfer"));
      });
    },

    retry: function () {
      var list = entries(), book = ledger(), bal = balances(), again = list[3];
      var run = data.ledger, replay = step(run, "retry with the same key");
      list.slice(0, 3).forEach(function (e) { book.post(e, { instant: true, cls: "earlier" }); bal.apply(e, true); });
      caption("Amara sends another " + money(again.amount) + ". The bank records it, but the connection drops before the reply reaches her app.");
      later(900, function () {
        var rows = book.post(again);
        rows[0].classList.add("lost");
        bal.apply(again);
        wire(step(run, "reply lost"));
      });
      later(3600, function () {
        caption("Her app cannot tell it worked, so it sends the same payment again with the same reference. The bank sees that reference was already used and sends back its first answer. Nothing is paid twice.");
        var ghosts = book.post({ ref: again.ref, legs: again.legs.map(function (l) {
          return { acct: l.acct, text: l.text + ", again", dr: l.dr, cr: l.cr };
        }) }, { ghost: true, cls: "ghost" });
        later(900, function () {
          ghosts.forEach(function (g, i) {
            var s = el("span", "strike");
            g.appendChild(s);
            later(i * 160, function () { s.classList.add("on"); });
          });
        });
        later(1600, function () {
          var stamp = el("div", "stamp");
          stamp.appendChild(el("b", null, "Refused as a duplicate. "));
          stamp.appendChild(document.createTextNode("Reference "));
          stamp.appendChild(el("code", null, again.key));
          stamp.appendChild(document.createTextNode(" was already used, so the bank sent back its first answer."));
          stamp.style.top = (ghosts[0].offsetTop + 4) + "px";
          book.body.appendChild(stamp);
          if (stamp.animate && !reduce.matches) {
            stamp.animate([{ opacity: 0, transform: "rotate(-1.5deg) scale(1.08)" }, { opacity: 1, transform: "rotate(-1.5deg) scale(1)" }],
              { duration: 220, easing: "cubic-bezier(.23,1,.32,1)" });
          }
          var amara = step(run, "Amara after the retry").response.body, ben = step(run, "Ben after the retry").response.body;
          bal.Amara.to(amara.balanceMinor); bal.Ben.to(ben.balanceMinor);
          bal.Amara.sub("unchanged by the second try"); bal.Ben.sub("unchanged by the second try");
          wire(replay); wire(step(run, "Amara after the retry")); wire(step(run, "Ben after the retry"));
        });
      });
    },

    reconcile: function () {
      var list = entries(), book = ledger(), r = step(data.ledger, "reconcile"), body = r.response.body;
      list.forEach(function (e) { book.post(e, { instant: true, cls: "earlier" }); });
      caption("A recount adds up every entry again, account by account, and checks the result against each saved balance.");
      var recon = el("div", "recon");
      recon.appendChild(row(["Lines", "Account, recounted", "Balance"], "head"));
      $("visual").appendChild(recon);
      var accts = ["World", "Amara", "Ben"];
      var rows = Array.prototype.slice.call(book.body.querySelectorAll(".row"));
      accts.forEach(function (name, i) {
        later(700 + i * 1400, function () {
          var mine = rows.filter(function (x) { return x.dataset.acct === name; });
          rows.forEach(function (x) { x.classList.toggle("lit", x.dataset.acct === name); });
          var sum = 0;
          list.forEach(function (e) { e.legs.forEach(function (l) { if (l.acct === name) sum += (l.cr || 0) - (l.dr || 0); }); });
          var line = row([String(mine.length), name, ""]);
          recon.appendChild(line); ink(line);
          Spring(0, function (x) { line.children[2].textContent = money(x); }).to(sum);
        });
      });
      later(700 + accts.length * 1400 + 200, function () {
        rows.forEach(function (x) { x.classList.remove("lit"); });
        caption("Every balance matches its entries. All balances together, the bank's own included, add up to exactly zero, so no money appeared or vanished.");
        var total = row(["", "All balances, the bank's own included", money(body.globalSumMinor)]);
        var wrap = el("div", "totals");
        wrap.appendChild(total); recon.appendChild(wrap); ink(total);
        figure("Books agree", 0, function () { return body.consistent ? "yes" : "no"; }).value.classList.add(body.consistent ? "good" : "neg");
        figure("Accounts checked", 0, count).to(body.accountsChecked);
        figure("Accounts that did not match", 0, count).to(body.drifts.length);
        wire(r);
      });
    },

    break: function () {
      var run = data.faults, s = run.summary, per = run.per_second, events = run.events;
      var page = $("visual");
      page.classList.add("plain");
      var last = Math.ceil(Math.max(per[per.length - 1].t + 1, events[events.length - 1][0]));
      caption("Eight customer apps keep sending transfers while the bank's servers are broken on purpose. First a server is shut down mid-payment. Then the database is cut off, and then it is shut down too.");

      var wrapEl = el("div", "tape-wrap"), tape = el("div", "tape");
      tape.tabIndex = 0;
      tape.setAttribute("role", "slider");
      tape.setAttribute("aria-label", "Position in the recorded crash test, in seconds");
      tape.setAttribute("aria-valuemin", "0");
      tape.setAttribute("aria-valuemax", String(last));
      var byT = {}; per.forEach(function (p) { byT[p.t] = p; });
      var peak = Math.max.apply(null, per.map(function (p) { return p.ok + p.retry; })) || 1;
      var secs = [], okCum = [0], retryCum = [0];
      for (var t = 0; t < last; t++) {
        var p = byT[t] || { ok: 0, retry: 0 }, sec = el("div", "sec");
        var ok = el("div", "ok"), retry = el("div", "retry");
        ok.style.height = (100 * p.ok / peak) + "%"; retry.style.height = (100 * p.retry / peak) + "%";
        sec.appendChild(ok); sec.appendChild(retry); tape.appendChild(sec); secs.push(sec);
        okCum.push(okCum[t] + p.ok); retryCum.push(retryCum[t] + p.retry);
      }
      function plain(e) {
        var x = e[2];
        if (/killed api pod/.test(x)) return ["Server shut down", "One of the bank's servers is shut down in the middle of payments. The apps lose their replies and send again with the same references."];
        if (/partitioned/.test(x)) return ["Database cut off for 10 s", "The servers lose their link to the database for 10 seconds. Nothing can be answered, so the apps keep trying."];
        if (/partition removed/.test(x)) return ["Link restored", "The link comes back. Repeats of payments that had already landed get the saved answer back, not a second payment."];
        if (/killed postgres/.test(x)) return ["Database shut down", "The database itself is shut down. Open links die with it, and the servers reconnect when it returns."];
        if (/postgres answering/.test(x)) return ["Database back", "The database answers again and transfers resume."];
        if (e[1] === "clients_done") return ["All transfers answered", "Every app has a clear answer for every transfer, " + count(s.keys) + " in all."];
        if (e[1] === "checked") return ["Books checked", "The test compares the database with what every app was told."];
        return [x, null];
      }
      events.forEach(function (e) {
        if (e[1] !== "fault" && e[1] !== "heal") return;
        var m = el("div", "mark" + (e[1] === "heal" ? " heal" : ""));
        m.style.left = (100 * e[0] / last) + "%";
        if (e[1] === "fault") m.appendChild(el("span", null, plain(e)[0]));
        tape.appendChild(m);
      });
      var head = el("div", "head-line");
      tape.appendChild(head);
      wrapEl.appendChild(tape);
      var axis = el("div", "axis");
      [0, Math.round(last / 2), last].forEach(function (v) { axis.appendChild(el("span", null, v + " s")); });
      wrapEl.appendChild(axis);
      var controls = el("div", "controls"), playBtn = el("button", "play", "Pause");
      playBtn.type = "button";
      controls.appendChild(el("p", "hint", "Plain bars are transfers answered each second. Red bars are attempts that failed or lost their reply. Drag along the tape, or use the arrow keys, to move through the run."));
      controls.appendChild(playBtn);
      wrapEl.appendChild(controls);
      page.appendChild(wrapEl);

      var journal = el("ol", "journal"), jl = [];
      events.forEach(function (e) {
        var li = el("li", e[1] === "fault" ? "fault" : "");
        li.appendChild(el("span", "t", e[0].toFixed(1) + "s"));
        li.appendChild(el("span", null, e[1] === "start" ? "The apps start sending transfers" : plain(e)[0]));
        journal.appendChild(li); jl.push(li);
      });
      page.appendChild(journal);
      var summary = el("div", "summary");
      page.appendChild(summary);

      var fSec = figure("Seconds into the run", 0, function (x) { return (x / 10).toFixed(1); });
      var fOk = figure("Transfers answered", 0, count), fRetry = figure("Second tries", 0, count);
      events.forEach(function (e) {
        var li = el("li");
        li.appendChild(el("code", null, e[0].toFixed(1) + "s  " + e[1] + "  " + e[2]));
        $("wire").appendChild(li);
      });

      var state = { t: 0, playing: false, raf: 0, settled: false, width: tape.clientWidth, lastEvent: -1, lastWhole: -1 };
      var ro = new ResizeObserver(function () { state.width = tape.clientWidth; render(); });
      ro.observe(tape);
      live.offs.push(function () { ro.disconnect(); });

      function render() {
        var t = state.t;
        head.style.transform = "translateX(" + (state.width * t / last) + "px)";
        var n = Math.min(last, Math.ceil(t - 1e-9));
        secs.forEach(function (sec, i) { sec.classList.toggle("on", i < n); });
        fSec.to(Math.round(t * 10), true);
        fOk.to(okCum[n]); fRetry.to(retryCum[n]);
        var cur = -1;
        events.forEach(function (e, i) { var past = e[0] <= t + 1e-9; jl[i].classList.toggle("past", past); if (past) cur = i; });
        jl.forEach(function (li, i) { li.classList.toggle("now", i === cur && events[i][1] === "fault"); });
        if (cur !== state.lastEvent) {
          if (cur > state.lastEvent && state.playing && cur > 0) { var said = plain(events[cur])[1]; if (said) caption(said); }
          state.lastEvent = cur;
        }
        var whole = Math.floor(t);
        if (whole !== state.lastWhole) {
          state.lastWhole = whole;
          tape.setAttribute("aria-valuenow", t.toFixed(1));
          tape.setAttribute("aria-valuetext", t.toFixed(0) + " seconds, " + count(okCum[n]) + " answered, " + count(retryCum[n]) + " retries");
        }
        playBtn.textContent = state.playing ? "Pause" : (state.t >= last ? "Replay" : "Play");
        if (t >= last && !state.settled) settle();
      }

      // The slow settle: once the run is over the red attempts fade to resolved, then the checks are written in.
      function settle() {
        state.settled = true;
        tape.classList.add("settled");
        later(reduce.matches ? 0 : 900, function () {
          caption("Afterwards every app has a clear answer, and the database agrees with every one. The recount finds the books at exactly zero, so nothing was lost or paid twice.");
          [["Transfers", count(s.keys)], ["Second tries", count(s.retries)],
           ["Money created or lost", count(s.conservation_violations), true], ["Transfers paid twice", count(s.double_applications), true],
           ["Transfers nobody asked for", count(s.phantom_transfers), true], ["Confirmed transfers missing", count(s.lost_acknowledged_transfers), true],
           ["Sum of every balance", money(s.reconciliation_global_sum_minor), true],
           ["Recount of the books", s.reconciliation_consistent ? "agrees" : "does not agree", s.reconciliation_consistent]]
            .forEach(function (c, i) {
              later(reduce.matches ? 0 : i * 260, function () {
                var r = el("div", "row");
                r.appendChild(el("span", "ref"));
                r.appendChild(el("span", "part", c[0]));
                r.appendChild(el("span", "fig" + (c[2] ? " zero" : ""), c[1]));
                summary.appendChild(r); ink(r);
              });
            });
        });
      }

      function tick(now) {
        state.raf = 0;
        if (!state.playing) return;
        var dt = Math.min((now - state.prev) / 1000, 0.1);
        state.prev = now;
        state.t = Math.min(last, state.t + dt * 5 * speed);
        if (state.t >= last) state.playing = false;
        render();
        if (state.playing) state.raf = frame(tick);
      }
      function play() {
        state.playing = state.t < last && !reduce.matches;
        if (state.playing && !state.raf) { state.prev = performance.now(); state.raf = frame(tick); }
      }
      if (reduce.matches) state.t = last;
      render();
      play();
      playBtn.onclick = function () {
        if (state.playing) { state.playing = false; render(); return; }
        if (state.t >= last) {
          state.t = 0; state.settled = false; state.lastEvent = -1;
          tape.classList.remove("settled"); summary.innerHTML = "";
        }
        state.playing = true;
        if (!state.raf) { state.prev = performance.now(); state.raf = frame(tick); }
        render();
      };

      // Scrubbing follows the pointer one to one and hands back to playback on release.
      function seek(clientX) {
        var box = tape.getBoundingClientRect();
        state.t = Math.max(0, Math.min(last, (clientX - box.left) / box.width * last));
        render();
      }
      var dragging = false, resume = false;
      function down(e) {
        dragging = true; resume = state.t < last; state.playing = false;
        tape.setPointerCapture(e.pointerId); seek(e.clientX);
      }
      function move(e) { if (dragging) seek(e.clientX); }
      function up() { if (!dragging) return; dragging = false; if (resume) play(); render(); }
      function key(e) {
        var stepBy = e.shiftKey ? 5 : 1, t0 = state.t;
        if (e.key === "ArrowRight" || e.key === "ArrowUp") state.t = Math.min(last, Math.floor(t0) + stepBy);
        else if (e.key === "ArrowLeft" || e.key === "ArrowDown") state.t = Math.max(0, Math.ceil(t0) - stepBy);
        else if (e.key === "Home") state.t = 0;
        else if (e.key === "End") state.t = last;
        else if (e.key === " " || e.key === "Enter") { e.preventDefault(); if (state.playing) state.playing = false; else play(); render(); return; }
        else return;
        e.preventDefault(); state.playing = false; render();
      }
      tape.addEventListener("pointerdown", down);
      tape.addEventListener("pointermove", move);
      tape.addEventListener("pointerup", up);
      tape.addEventListener("pointercancel", up);
      tape.addEventListener("keydown", key);
    },

    flag: function () {
      var run = data.mule, page = $("visual");
      page.classList.add("unruled");
      var ids = {};
      run.steps.forEach(function (s) { if (/^open /.test(s.label)) ids[s.response.body.id] = s.label.slice(5); });
      var usual = run.steps.filter(function (s) { return /usual shopping/.test(s.label); }).map(function (s) { return s.request.body; });
      var names = ["Victim", "Mule one", "Mule two", "Mule three"];
      var scores = names.map(function (n) { return step(run, "risk for " + n); });
      var threshold = scores[0].response.body.flagThreshold;
      var RULES = {
        amount_deviation: "far above her usual amount",
        new_counterparty: "a payee she has never paid",
        round_amount: "a round amount",
        forwards_flagged: "passes on flagged money within the hour"
      };
      function hhmm(iso) { return iso.slice(11, 16); }
      function minutes(a, b) { return Math.round((Date.parse(b) - Date.parse(a)) / 60000); }

      caption("A victim with ordinary habits sends a large payment to an account she has never paid. That account belongs to a money mule, who passes it on within minutes. So does the next mule, and the next.");
      var chain = el("ol", "chain"), hops = [];
      names.concat(["Cash-out"]).forEach(function (name) {
        var li = el("li", "hop"), rail = el("div", "rail"), body = el("div", "body");
        rail.appendChild(el("span", "node")); rail.appendChild(el("span", "edge"));
        body.appendChild(el("div", "who", name));
        li.appendChild(rail); li.appendChild(body); chain.appendChild(li);
        hops.push({ li: li, body: body, edge: rail.lastChild });
      });
      page.appendChild(chain);

      var fFlag = figure("Payments flagged", 0, function (x) { return Math.round(x) + " of " + names.length; });
      var first = scores[0].response.body.scores[0];
      figure("Her payment to " + ids[first.counterpartyAccountId], 0, money).to(first.amountMinor);
      var fMin = figure("Minutes from her payment to the payout", 0, count);
      figure("Score that raises a flag", 0, count).to(threshold);

      var flagged = 0;
      scores.forEach(function (r, i) {
        later(700 + i * 1700, function () {
          var sc = r.response.body.scores[0], x = sc.explanation, h = hops[i];
          var what = el("p", "what");
          what.appendChild(document.createTextNode("Pays " + ids[sc.counterpartyAccountId] + " "));
          what.appendChild(el("span", "fig", money(sc.amountMinor)));
          what.appendChild(document.createTextNode(" at " + hhmm(sc.eventAt) + (i > 0 ? ", " + minutes(scores[i - 1].response.body.scores[0].eventAt, sc.eventAt) + " minutes after the money came in" : "")));
          h.body.appendChild(what);
          if (i === 0 && usual.length) {
            var u = el("div", "usual");
            var amounts = usual.map(function (b) { return b.amountMinor; });
            var payees = usual.map(function (b) { return ids[b.toAccountId]; }).filter(function (v, k, a) { return a.indexOf(v) === k; });
            u.appendChild(el("span", null, "Usually pays " + payees.join(" and ") + ", " + usual.length + " payments of " + money(Math.min.apply(null, amounts)) + " to " + money(Math.max.apply(null, amounts))));
            h.body.appendChild(u);
          }
          h.body.appendChild(el("div", "score", "Suspicion score " + sc.score + (sc.flagged ? ", at or over the line of " : ", under the line of ") + threshold + (sc.flagged ? ", so it is flagged." : ", so it passes.")));
          var ul = el("ul");
          Object.keys(x.points).forEach(function (k) {
            var item = el("li");
            item.appendChild(el("span", null, RULES[k] || k.replace(/_/g, " ")));
            item.appendChild(el("span", "pts", (x.points[k] > 0 ? "+" : "") + x.points[k]));
            ul.appendChild(item);
          });
          h.body.appendChild(ul);
          if (x.evidence.length) h.body.appendChild(el("p", "because", "Based on entry #" + x.evidence.join(", #") + ", the flagged payment that came in."));
          h.body.classList.add("on");
          if (sc.flagged) { h.li.classList.add("flagged"); flagged++; fFlag.to(flagged); }
          wire(r);
          later(reduce.matches ? 0 : 450, function () { h.edge.classList.add("on"); });
          if (i === scores.length - 1) {
            later(900, function () {
              var exit = hops[names.length];
              var p = el("p", "what");
              p.appendChild(document.createTextNode("Receives "));
              p.appendChild(el("span", "fig", money(sc.amountMinor)));
              p.appendChild(document.createTextNode(" at " + hhmm(sc.eventAt) + ", the end of the chain."));
              exit.body.appendChild(p); exit.body.classList.add("on");
              fMin.to(minutes(first.eventAt, sc.eventAt));
              caption("Every account in the chain is flagged. Each alert on a mule names the flagged payment that led to it. The checks run after the money has moved, so they never slow a transfer down.");
            });
          }
        });
      });
    }
  };

  function nums(text) { return (String(text).match(/[\d,.]+/g) || []).map(function (x) { return parseFloat(x.replace(/,/g, "")); }); }
  function about(n) {
    var p = Math.pow(10, Math.max(0, Math.floor(Math.log10(Math.abs(n) || 1)) - 1));
    return count(Math.round(n / p) * p);
  }

  // numbers.json is written by eval/report.py in the words of the results files. The page says the
  // same figures in plain words, matched on stable parts of each label. An unknown tile shows as written.
  var PLAIN = [
    [/^States/, function (t) { return { label: "Situations a proof tool checked", value: t.value,
      was: "It found no way to lose or double money. Remove the duplicate check on purpose and it finds one at once." }; }],
    [/^Transfers under/, function (t) {
      var w = nums(t.was), bad = w[0], runs = w[1];
      return { label: "Transfers made while the servers were broken on purpose", value: t.value,
        was: bad === 0 ? "None was lost or paid twice in " + runs + " crash tests, which is what a bank needs."
          : count(bad) + " went wrong in " + runs + " crash tests. That is not good enough yet." };
    }],
    [/cluster$/, function (t) { return { label: "Slowest 1 in 100 transfers took", value: t.value,
      was: "On one laptop running a test copy of the bank, handling " + nums(t.was)[0] + " transfers a second. That is quicker than a blink." }; }],
    [/^Burst alert/, function (t) {
      var now = parseFloat(t.value), then = nums(t.was)[0];
      return { label: "Of the transfers flagged as suspicious, this share really were", value: (now * 100).toFixed(2) + "%",
        was: "The first version flagged mostly innocent ones. Only about " + Math.round(then * 100) + " in 100 of its flags were right." };
    }],
    [/^Normal payments/, function (t) {
      var w = nums(t.was);
      return { label: "Ordinary payments wrongly flagged", value: t.value,
        was: "This is out of about " + about(w[1]) + " ordinary payments. The first version flagged " + count(w[0]) + ". Fewer is better." };
    }]
  ];
  function plainTile(t) {
    for (var i = 0; i < PLAIN.length; i++) if (PLAIN[i][0].test(t.label)) return PLAIN[i][1](t);
    return t;
  }

  // The figure counts up from zero the first time it scrolls into view. It is written in full first,
  // so a reader who never scrolls, or asked for less motion, still sees the real value.
  function countUp(dd, text) {
    dd.textContent = text;
    var m = /^([\d,]*\.?\d+)(.*)$/.exec(text);
    if (!m || reduce.matches || !window.IntersectionObserver) return;
    var target = parseFloat(m[1].replace(/,/g, "")), dp = (m[1].split(".")[1] || "").length, rest = m[2];
    var io = new IntersectionObserver(function (seen) {
      if (!seen[0].isIntersecting) return;
      io.disconnect();
      var t0 = performance.now();
      (function go(now) {
        var k = Math.min(1, (now - t0) / 1100), e = 1 - Math.pow(1 - k, 3), v = target * e;
        dd.textContent = (dp ? v.toLocaleString("en-US", { minimumFractionDigits: dp, maximumFractionDigits: dp }) : count(v)) + rest;
        if (k < 1) requestAnimationFrame(go); else dd.textContent = text;
      })(t0);
    }, { threshold: 0.6 });
    io.observe(dd);
  }

  function tiles() {
    var n = data.numbers, box = $("tiles");
    box.innerHTML = "";
    n.tiles.map(plainTile).forEach(function (t) {
      var d = el("div");
      d.appendChild(el("dt", null, t.label));
      var dd = el("dd");
      d.appendChild(dd);
      countUp(dd, t.value);
      if (t.was) d.appendChild(el("p", "was", t.was));
      box.appendChild(d);
    });
    var src = $("numbers-source");
    src.textContent = "Every figure here is read from ";
    n.sources.forEach(function (s, i) {
      src.appendChild(el("code", null, s));
      src.appendChild(document.createTextNode(i < n.sources.length - 1 ? ", " : ", files in the repository."));
    });
  }

  var buttons = Array.prototype.slice.call(document.querySelectorAll(".tabs button"));
  function show(name) {
    reset();
    buttons.forEach(function (b) { b.setAttribute("aria-pressed", String(b.dataset.run === name)); });
    var btn = buttons.filter(function (b) { return b.dataset.run === name; })[0];
    $("folio-title").textContent = btn.textContent;
    if (location.hash.slice(1) !== name && history.replaceState) history.replaceState(null, "", "#" + name);
    runs[name]();
  }

  var files = { ledger: "data/ledger-run.json", mule: "data/mule-run.json", faults: "data/faults-run.json", numbers: "data/numbers.json" };
  function load() {
    $("book").setAttribute("aria-busy", "true");
    Promise.all(Object.keys(files).map(function (k) {
      return fetch(files[k]).then(function (r) {
        if (!r.ok) throw new Error(files[k] + " returned " + r.status);
        return r.json();
      }).then(function (j) { data[k] = j; });
    })).then(function () {
      $("book").setAttribute("aria-busy", "false");
      tiles();
      $("provenance").textContent = "Everything below is a replay of the real system, recorded on " + data.ledger.recorded_on + ". Nothing on this page talks to a live server.";
      buttons.forEach(function (b) {
        b.disabled = false;
        b.onclick = function () { show(b.dataset.run); };
      });
      var start = location.hash.slice(1);
      show(runs[start] ? start : "transfer");
    }).catch(function (err) {
      $("book").setAttribute("aria-busy", "false");
      $("folio-title").textContent = "The book did not open";
      $("caption").textContent = "The recorded runs did not load (" + err.message + ").";
      var box = el("p", "error", "Check the connection and try again.");
      var again = el("button", null, "Try again");
      again.type = "button";
      again.onclick = function () { box.remove(); load(); };
      box.appendChild(again);
      $("visual").innerHTML = ""; $("visual").appendChild(box);
    });
  }
  // The page turns from paper to ink once, when the results come up. The class only swaps two colours,
  // the CSS does the tween, and reduced motion drops the transition so the flip is instant.
  if (window.IntersectionObserver) {
    var inView = {};
    var flipper = new IntersectionObserver(function (seen) {
      seen.forEach(function (s) { inView[s.target.id] = s.isIntersecting; });
      document.body.classList.toggle("flip", inView.numbers || inView.colophon);
    }, { rootMargin: "-45% 0px -45% 0px" });
    flipper.observe($("numbers")); flipper.observe($("colophon"));
  }

  // The raw requests are long JSON lines. On a phone they start folded so the ledger stays in view.
  if (window.matchMedia("(max-width: 720px)").matches) $("wire-box").open = false;
  window.addEventListener("hashchange", function () {
    var name = location.hash.slice(1);
    if (runs[name] && data.numbers) show(name);
  });
  load();
})();
