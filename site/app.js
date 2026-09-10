/* Plays back the recorded runs in data/. Every number shown is read from those files, none is typed here. */
(function () {
  "use strict";

  var data = {};
  var timers = [];
  var $ = function (id) { return document.getElementById(id); };

  function money(minor) {
    var sign = minor < 0 ? "-" : "";
    var v = Math.abs(minor);
    return sign + Math.floor(v / 100).toLocaleString("en-US") + "." + String(v % 100).padStart(2, "0");
  }

  function el(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text !== undefined) e.textContent = text;
    return e;
  }

  function later(ms, fn) { timers.push(setTimeout(fn, ms)); }
  function reset() { timers.forEach(clearTimeout); timers = []; $("visual").innerHTML = ""; $("wire").innerHTML = ""; }
  function caption(text) { $("caption").textContent = text; }

  function wire(step) {
    var li = el("li");
    if (step.note) {
      li.appendChild(el("span", "badge bad", "lost"));
      li.appendChild(document.createTextNode(" " + step.note));
    } else {
      var r = step.request, s = step.response;
      var head = r.method + " " + r.path + (r.headers["Idempotency-Key"] ? "  Idempotency-Key: " + r.headers["Idempotency-Key"] : "");
      li.appendChild(el("code", null, head));
      li.appendChild(document.createTextNode("  → " + s.status + (s.headers["idempotency-replayed"] ? ", Idempotency-Replayed: true" : "")));
      if (s.body) li.appendChild(el("div", null)).appendChild(el("code", null, JSON.stringify(s.body)));
    }
    $("wire").appendChild(li);
  }

  function step(run, label) {
    return run.steps.filter(function (s) { return s.label === label; })[0];
  }

  function accounts(list) {
    var box = el("div", "accounts");
    list.forEach(function (a) {
      var card = el("div", "acct" + (a.hit ? " hit" : ""));
      card.appendChild(el("div", "name", a.name));
      card.appendChild(el("div", "bal", money(a.balance)));
      box.appendChild(card);
    });
    $("visual").innerHTML = "";
    $("visual").appendChild(box);
  }

  var runs = {
    transfer: function () {
      var run = data.ledger, a = step(run, "open Amara"), b = step(run, "open Ben"), t = step(run, "transfer");
      var amount = t.request.body.amountMinor;
      caption("Amara and Ben each open an account. The opening money comes from the bank's own world account, as a balanced entry.");
      accounts([{ name: "Amara", balance: a.response.body.balanceMinor }, { name: "Ben", balance: b.response.body.balanceMinor }]);
      [a, b].forEach(wire);
      later(1800, function () {
        caption("Amara sends Ben " + money(amount) + ". That is two entries, minus " + money(amount) + " for Amara and plus " + money(amount) + " for Ben, and they cancel out.");
        accounts([{ name: "Amara", balance: a.response.body.balanceMinor - amount, hit: true },
                  { name: "Ben", balance: b.response.body.balanceMinor + amount, hit: true }]);
        wire(t);
        wire(step(run, "balances after the transfer"));
      });
    },
    retry: function () {
      var run = data.ledger, t = step(run, "transfer"), lost = step(run, "reply lost"), again = step(run, "retry with the same key");
      var amara = step(run, "Amara after the retry").response.body, ben = step(run, "Ben after the retry").response.body;
      var a0 = step(run, "open Amara").response.body.balanceMinor - t.request.body.amountMinor;
      var b0 = step(run, "open Ben").response.body.balanceMinor + t.request.body.amountMinor;
      caption("Amara sends another " + money(again.request.body.amountMinor) + ". The server takes the money, but the reply is lost on the way back.");
      accounts([{ name: "Amara", balance: a0 }, { name: "Ben", balance: b0 }]);
      wire(lost);
      later(2000, function () {
        caption("Her app retries with the same key. The server sees it already did this and answers with the first result, marked as a replay. The money moved once.");
        accounts([{ name: "Amara", balance: amara.balanceMinor, hit: true }, { name: "Ben", balance: ben.balanceMinor, hit: true }]);
        wire(again);
        wire(step(run, "Amara after the retry"));
        wire(step(run, "Ben after the retry"));
      });
    },
    reconcile: function () {
      var r = step(data.ledger, "reconcile"), body = r.response.body;
      caption("Reconcile rebuilds every balance from the individual entries and compares it with what is stored.");
      var box = el("div", "checks");
      [["Books consistent", body.consistent ? "yes" : "no"], ["Sum of every balance, world included", money(body.globalSumMinor)],
       ["Accounts checked", String(body.accountsChecked)], ["Accounts that drifted", String(body.drifts.length)]]
        .forEach(function (c) { var d = el("div", "check"); d.appendChild(el("div", "small", c[0])); d.appendChild(el("b", null, c[1])); box.appendChild(d); });
      $("visual").appendChild(box);
      wire(r);
    },
    break: function () {
      var run = data.faults, s = run.summary, per = run.per_second;
      var last = Math.max(per[per.length - 1].t, run.events[run.events.length - 1][0]) + 1;
      caption("Eight clients send transfers to the service on Kubernetes while it is broken on purpose: a server killed mid-request, the database cut off, then the database killed.");
      var tl = el("div", "timeline");
      var peak = Math.max.apply(null, per.map(function (p) { return p.ok + p.retry; })) || 1;
      var byT = {}; per.forEach(function (p) { byT[p.t] = p; });
      var bars = [];
      for (var t = 0; t < last; t++) {
        var p = byT[t] || { ok: 0, retry: 0 }, bar = el("div", "bar");
        var ok = el("div", "ok"), retry = el("div", "retry");
        ok.style.height = "0"; retry.style.height = "0";
        bar.appendChild(ok); bar.appendChild(retry);
        tl.appendChild(bar);
        bars.push([ok, retry, p]);
      }
      run.events.filter(function (e) { return e[1] === "fault"; }).forEach(function (e) {
        var m = el("div", "mark"); m.style.left = (100 * e[0] / last) + "%";
        m.appendChild(el("span", null, e[2].replace(/ tally-api-[\w-]+/, "").replace(" for ", ", ")));
        tl.appendChild(m);
      });
      $("visual").appendChild(tl);
      $("visual").appendChild(el("div", "small", "Each bar is one second. Green: transfers answered. Red: attempts that failed or lost their reply, each retried with the same key."));
      bars.forEach(function (b, i) {
        later(i * 60, function () {
          b[0].style.height = (100 * b[2].ok / peak) + "%";
          b[1].style.height = (100 * b[2].retry / peak) + "%";
        });
      });
      later(bars.length * 60 + 400, function () {
        caption("Afterwards every client has a definite answer, and the database agrees with every one of them.");
        var box = el("div", "checks");
        [["Transfers", s.keys], ["Retries", s.retries], ["Money created or lost", s.conservation_violations],
         ["Transfers applied twice", s.double_applications], ["Transfers nobody asked for", s.phantom_transfers],
         ["Confirmed transfers missing", s.lost_acknowledged_transfers], ["Reconciliation", s.reconciliation_consistent ? "consistent" : "broken"]]
          .forEach(function (c) { var d = el("div", "check"); d.appendChild(el("div", "small", c[0])); d.appendChild(el("b", null, String(c[1]))); box.appendChild(d); });
        $("visual").appendChild(box);
      });
      run.events.forEach(function (e) { var li = el("li"); li.appendChild(el("code", null, e[0].toFixed(1) + "s  " + e[1] + "  " + e[2])); $("wire").appendChild(li); });
    },
    flag: function () {
      var run = data.mule, who = ["Victim", "Mule one", "Mule two", "Mule three"];
      var ids = {};
      run.steps.forEach(function (s) { if (/^open /.test(s.label)) ids[s.response.body.id] = s.label.slice(5); });
      caption("A victim with ordinary habits sends a large payment to an account that forwards it within minutes, and again, and again.");
      var chain = el("div", "chain"), cards = [];
      who.forEach(function (name) {
        var c = el("div", "hop"); c.appendChild(el("div", "who", name)); chain.appendChild(c); cards.push(c);
      });
      $("visual").appendChild(chain);
      who.forEach(function (name, i) {
        later(900 + i * 1400, function () {
          var r = step(run, "risk for " + name), sc = r.response.body.scores[0], x = sc.explanation, c = cards[i];
          c.classList.add("on");
          if (sc.flagged) c.classList.add("flagged");
          c.appendChild(el("div", null, "pays " + ids[sc.counterpartyAccountId] + " " + money(sc.amountMinor)));
          c.appendChild(el("span", "badge" + (sc.flagged ? " bad" : ""), "score " + sc.score + (sc.flagged ? ", flagged" : "")));
          var ul = el("ul");
          Object.keys(x.points).forEach(function (k) { ul.appendChild(el("li", null, k.replace(/_/g, " ") + " " + (x.points[k] > 0 ? "+" : "") + x.points[k])); });
          if (x.evidence.length) ul.appendChild(el("li", null, "because of posting #" + x.evidence.join(", #") + ", the flagged money that just came in"));
          c.appendChild(ul);
          wire(r);
          if (i === who.length - 1) caption("Every hop is flagged, and each alert names the flagged payment it rests on. Scoring runs after the money has moved, so it never slows a transfer down.");
        });
      });
    }
  };

  function tiles() {
    var n = data.numbers, box = $("tiles");
    n.tiles.forEach(function (t) {
      var d = el("div", "tile");
      d.appendChild(el("div", "small", t.label));
      d.appendChild(el("div", "big", t.value));
      if (t.was) d.appendChild(el("div", "was", t.was));
      box.appendChild(d);
    });
    $("numbers-source").textContent = "From " + n.sources.join(", ") + " in the repository.";
  }

  function show(name) {
    reset();
    document.querySelectorAll(".buttons button").forEach(function (b) { b.setAttribute("aria-pressed", String(b.dataset.run === name)); });
    runs[name]();
  }

  var files = { ledger: "data/ledger-run.json", mule: "data/mule-run.json", faults: "data/faults-run.json", numbers: "data/numbers.json" };
  Promise.all(Object.keys(files).map(function (k) {
    return fetch(files[k]).then(function (r) { return r.json(); }).then(function (j) { data[k] = j; });
  })).then(function () {
    tiles();
    document.querySelectorAll(".buttons button").forEach(function (b) { b.addEventListener("click", function () { show(b.dataset.run); }); });
    var start = (location.hash || "#transfer").slice(1);
    show(runs[start] ? start : "transfer");
  });
})();
