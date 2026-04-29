/*
 * THIS IS NOT THE REAL TALLY SERVER. The real one is Java and keeps its data in Postgres.
 *
 * This is a copy of that service's rules as a window.fetch interceptor, so the real React client in
 * this folder runs on a static host with nothing to install. Every rule below names the Java file it
 * was transcribed from. Nothing here reaches a network.
 *
 * Missing on purpose, because a browser has neither: locks (one thread, so no two transfers can
 * interleave) and durability (closing the tab empties the book). The bearer token, the rate limits and
 * the cursor signature are all ignored too. A page with no server has nothing to protect and no key to
 * sign with, though the cursor keeps the same opaque "v1:" shape and the same INVALID_CURSOR refusal.
 */
(function () {
  "use strict";

  var WORLD_ID = "00000000-0000-0000-0000-000000000000";
  var MAX_AMOUNT_MINOR = 1000000000000; // 10^12, Validation.java line 22
  var MAX_NAME_LENGTH = 80; // Validation.java line 23
  var DEFAULT_LIMIT = 50; // AccountsHandler.java line 30
  var MAX_LIMIT = 200; // AccountsHandler.java line 31
  var KEY_FORMAT = /^[A-Za-z0-9_-]{8,64}$/; // Validation.java line 30
  var NAME_CHARSET = /^[\p{L}\p{N} .,'&-]+$/u; // Validation.java line 32
  var UUID_FORMAT = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;

  var ACCOUNT_FIELDS = ["name", "openingBalanceMinor"];
  var TRANSFER_FIELDS = ["fromAccountId", "toAccountId", "amountMinor"];

  // ErrorCode.java: the code to HTTP status mapping, copied entry for entry.
  var STATUS = {
    MALFORMED_JSON: 400,
    BODY_NOT_OBJECT: 400,
    NAME_REQUIRED: 400,
    NAME_LENGTH: 400,
    NAME_CHARSET: 400,
    OPENING_BALANCE_NOT_INTEGER: 400,
    OPENING_BALANCE_NEGATIVE: 400,
    OPENING_BALANCE_TOO_LARGE: 400,
    FROM_ACCOUNT_ID_REQUIRED: 400,
    TO_ACCOUNT_ID_REQUIRED: 400,
    FROM_ACCOUNT_ID_INVALID: 400,
    TO_ACCOUNT_ID_INVALID: 400,
    AMOUNT_REQUIRED: 400,
    AMOUNT_NOT_INTEGER: 400,
    AMOUNT_NOT_POSITIVE: 400,
    AMOUNT_TOO_LARGE: 400,
    SAME_ACCOUNT: 400,
    UNKNOWN_FIELD: 400,
    INVALID_LIMIT: 400,
    INVALID_CURSOR: 400,
    IDEMPOTENCY_KEY_MISSING: 400,
    IDEMPOTENCY_KEY_INVALID: 400,
    IDEMPOTENCY_KEY_CONFLICT: 409,
    UNKNOWN_ACCOUNT: 422,
    INSUFFICIENT_FUNDS: 422,
    RESERVED_ACCOUNT: 422,
    ACCOUNT_NOT_FOUND: 404,
    NOT_FOUND: 404,
    METHOD_NOT_ALLOWED: 405,
    INTERNAL: 500
  };

  /* ---------------------------------------------------------------- helpers */

  function uuid() {
    if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") {
      return crypto.randomUUID();
    }
    // Only reached in a non secure context, where the client itself would already be broken.
    var bytes = new Uint8Array(16);
    if (typeof crypto !== "undefined" && crypto.getRandomValues) {
      crypto.getRandomValues(bytes);
    } else {
      for (var i = 0; i < 16; i++) {
        bytes[i] = Math.floor(Math.random() * 256);
      }
    }
    bytes[6] = (bytes[6] & 0x0f) | 0x40;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;
    var hex = [];
    for (var j = 0; j < 16; j++) {
      hex.push((bytes[j] + 0x100).toString(16).slice(1));
    }
    return (
      hex.slice(0, 4).join("") + "-" + hex.slice(4, 6).join("") + "-" +
      hex.slice(6, 8).join("") + "-" + hex.slice(8, 10).join("") + "-" + hex.slice(10, 16).join("")
    );
  }

  function nowIso() {
    return new Date().toISOString();
  }

  // Thrown for anything the real service answers with an error envelope.
  function ApiError(code, message, field) {
    this.isApiError = true;
    this.code = code;
    this.message = message;
    this.field = field;
  }

  function reject(code, message, field) {
    throw new ApiError(code, message, field);
  }

  /* -------------------------------------------------- the book (InMemoryStore.java) */

  var accounts = new Map();
  var creationOrder = []; // world is never in here, so the listing excludes it without a filter
  var journal = new Map(); // accountId -> entries, ascending postingId
  var postingSeq = 1;
  var reservations = new Map(); // idempotency key -> { from, to, amount, outcome }

  // WorldAccount.java: the nil UUID, the one account allowed below zero, created at the epoch.
  accounts.set(WORLD_ID, {
    id: WORLD_ID,
    name: "world",
    balanceMinor: 0,
    allowNegative: true,
    createdAt: "1970-01-01T00:00:00Z"
  });

  function journalFor(id) {
    var lines = journal.get(id);
    if (lines === undefined) {
      lines = [];
      journal.set(id, lines);
    }
    return lines;
  }

  // Two entries per movement, one negative and one positive, so they always cancel.
  function recordPostings(transferId, fromId, toId, amount, newFrom, newTo, at) {
    var fromPostingId = postingSeq++;
    var toPostingId = postingSeq++;
    journalFor(fromId).push({
      postingId: fromPostingId,
      transferId: transferId,
      counterpartyAccountId: toId,
      amountMinor: -amount,
      balanceAfterMinor: newFrom,
      createdAt: at
    });
    journalFor(toId).push({
      postingId: toPostingId,
      transferId: transferId,
      counterpartyAccountId: fromId,
      amountMinor: amount,
      balanceAfterMinor: newTo,
      createdAt: at
    });
  }

  // An opening balance is moved out of world, never minted, through the same two entry rule an
  // ordinary payment uses. That is why the whole book sums to zero from the very first account.
  function createAccount(name, openingBalanceMinor) {
    var fresh = {
      id: uuid(),
      name: name,
      balanceMinor: 0,
      allowNegative: false,
      createdAt: nowIso()
    };
    accounts.set(fresh.id, fresh);
    creationOrder.push(fresh.id);
    if (openingBalanceMinor > 0) {
      var world = accounts.get(WORLD_ID);
      world.balanceMinor -= openingBalanceMinor;
      fresh.balanceMinor += openingBalanceMinor;
      recordPostings(uuid(), WORLD_ID, fresh.id, openingBalanceMinor,
        world.balanceMinor, fresh.balanceMinor, nowIso());
    }
    return fresh;
  }

  // Ledger.java plus InMemoryStore.applyBalances: check first, then write both sides together.
  function evaluate(req) {
    if (!accounts.has(req.from)) {
      return { kind: "unknownAccount", account: req.from };
    }
    if (!accounts.has(req.to)) {
      return { kind: "unknownAccount", account: req.to };
    }
    if (req.from === WORLD_ID) {
      return { kind: "reservedAccount", account: req.from };
    }
    if (req.to === WORLD_ID) {
      return { kind: "reservedAccount", account: req.to };
    }
    var from = accounts.get(req.from);
    var to = accounts.get(req.to);
    var newFrom = from.balanceMinor - req.amount;
    if (newFrom < 0 && !from.allowNegative) {
      return {
        kind: "insufficientFunds",
        account: from.id,
        balanceMinor: from.balanceMinor,
        requestedMinor: req.amount
      };
    }
    var newTo = to.balanceMinor + req.amount;
    from.balanceMinor = newFrom;
    to.balanceMinor = newTo;
    var transferId = uuid();
    var at = nowIso();
    recordPostings(transferId, from.id, to.id, req.amount, newFrom, newTo, at);
    return { kind: "applied", id: transferId, at: at };
  }

  // InMemoryStore.consumes. Only an outcome that wrote something is worth replaying, so an unknown
  // account leaves the key free for a later real transfer.
  function consumes(outcome) {
    return outcome.kind === "applied" || outcome.kind === "insufficientFunds";
  }

  // InMemoryStore.apply. The slot is claimed before the outcome is known, which is what makes the
  // first caller the winner rather than the fastest one to finish.
  function applyTransfer(req) {
    var existing = reservations.get(req.key);
    if (existing === undefined) {
      var slot = { from: req.from, to: req.to, amount: req.amount, outcome: null };
      reservations.set(req.key, slot);
      var outcome = evaluate(req);
      slot.outcome = outcome;
      if (!consumes(outcome)) {
        reservations.delete(req.key);
      }
      return outcome;
    }
    var sameTuple = existing.from === req.from && existing.to === req.to && existing.amount === req.amount;
    if (!sameTuple) {
      return { kind: "keyConflict" };
    }
    return consumes(existing.outcome) ? { kind: "replayed", first: existing.outcome } : existing.outcome;
  }

  // InMemoryStore.statement: newest first, one extra matching row past the limit means another page.
  function statement(id, before, limit) {
    var lines = journal.get(id) || [];
    var page = [];
    var hasMore = false;
    for (var i = lines.length - 1; i >= 0; i--) {
      var line = lines[i];
      if (line.postingId < before) {
        if (page.length === limit) {
          hasMore = true;
          break;
        }
        page.push(line);
      }
    }
    return { entries: page, hasMore: hasMore };
  }

  // InMemoryStore.reconcile. The real store sorts ids the way Java orders UUIDs, but only the count of
  // them is ever reported, so a plain string sort here changes nothing an answer can rest on.
  function reconcile() {
    var ids = Array.from(accounts.keys()).sort();
    var drifts = [];
    var globalSum = 0;
    ids.forEach(function (id) {
      var stored = accounts.get(id).balanceMinor;
      var derived = (journal.get(id) || []).reduce(function (sum, line) {
        return sum + line.amountMinor;
      }, 0);
      if (stored !== derived) {
        drifts.push({
          accountId: id,
          storedBalanceMinor: stored,
          derivedBalanceMinor: derived,
          driftMinor: stored - derived
        });
      }
      globalSum += stored;
    });
    return {
      consistent: globalSum === 0 && drifts.length === 0,
      globalSumMinor: globalSum,
      accountsChecked: ids.length,
      drifts: drifts
    };
  }

  /* ------------------------------------------------- request checks (Validation.java) */

  // The real service parses JSON with a hand written, integer only parser, so a fractional or
  // exponent number never reaches a handler. JSON.parse is looser, so the check happens here.
  function parseBody(raw) {
    var value;
    try {
      value = JSON.parse(raw === null || raw === undefined || raw === "" ? "null" : raw);
    } catch (bad) {
      reject("MALFORMED_JSON", "request body is not valid JSON");
    }
    walkNumbers(value);
    return value;
  }

  function walkNumbers(value) {
    if (typeof value === "number") {
      if (!Number.isSafeInteger(value)) {
        reject("MALFORMED_JSON", "numbers must be whole");
      }
      return;
    }
    if (Array.isArray(value)) {
      value.forEach(walkNumbers);
      return;
    }
    if (value !== null && typeof value === "object") {
      Object.keys(value).forEach(function (key) {
        walkNumbers(value[key]);
      });
    }
  }

  function requireObject(body) {
    if (body === null || typeof body !== "object" || Array.isArray(body)) {
      reject("BODY_NOT_OBJECT", "request body must be a JSON object");
    }
    return body;
  }

  // Unknown fields are refused rather than ignored, so a typo is caught where it happened.
  function rejectUnknownFields(obj, known) {
    Object.keys(obj).forEach(function (key) {
      if (known.indexOf(key) === -1) {
        reject("UNKNOWN_FIELD", "unknown field: " + key, key);
      }
    });
  }

  function accountFields(body) {
    var obj = requireObject(body);
    rejectUnknownFields(obj, ACCOUNT_FIELDS);
    if (typeof obj.name !== "string") {
      reject("NAME_REQUIRED", "name is required and must be a string", "name");
    }
    var name = obj.name.trim();
    if (name.length === 0 || name.length > MAX_NAME_LENGTH) {
      reject("NAME_LENGTH", "name must be 1 to 80 characters", "name");
    }
    if (!NAME_CHARSET.test(name)) {
      reject("NAME_CHARSET", "name may use letters, digits, spaces and . , ' & -", "name");
    }
    var opening = 0;
    if (obj.openingBalanceMinor !== undefined) {
      if (typeof obj.openingBalanceMinor !== "number") {
        reject("OPENING_BALANCE_NOT_INTEGER",
          "openingBalanceMinor must be a whole number of minor units", "openingBalanceMinor");
      }
      opening = obj.openingBalanceMinor;
    }
    if (opening < 0) {
      reject("OPENING_BALANCE_NEGATIVE", "openingBalanceMinor must not be negative", "openingBalanceMinor");
    }
    if (opening > MAX_AMOUNT_MINOR) {
      reject("OPENING_BALANCE_TOO_LARGE",
        "openingBalanceMinor must be at most " + MAX_AMOUNT_MINOR + " minor units", "openingBalanceMinor");
    }
    return { name: name, openingBalanceMinor: opening };
  }

  function requiredId(obj, field, missingCode, invalidCode) {
    if (typeof obj[field] !== "string") {
      reject(missingCode, field + " is required and must be a string", field);
    }
    if (!UUID_FORMAT.test(obj[field])) {
      reject(invalidCode, field + " must be an account id", field);
    }
    return obj[field];
  }

  function transferFields(body) {
    var obj = requireObject(body);
    rejectUnknownFields(obj, TRANSFER_FIELDS);
    var from = requiredId(obj, "fromAccountId", "FROM_ACCOUNT_ID_REQUIRED", "FROM_ACCOUNT_ID_INVALID");
    var to = requiredId(obj, "toAccountId", "TO_ACCOUNT_ID_REQUIRED", "TO_ACCOUNT_ID_INVALID");
    if (from === to) {
      reject("SAME_ACCOUNT", "fromAccountId and toAccountId must be different accounts", "toAccountId");
    }
    if (obj.amountMinor === undefined) {
      reject("AMOUNT_REQUIRED", "amountMinor is required", "amountMinor");
    }
    if (typeof obj.amountMinor !== "number") {
      reject("AMOUNT_NOT_INTEGER", "amountMinor must be a whole number of minor units", "amountMinor");
    }
    if (obj.amountMinor < 1) {
      reject("AMOUNT_NOT_POSITIVE", "amountMinor must be greater than zero", "amountMinor");
    }
    if (obj.amountMinor > MAX_AMOUNT_MINOR) {
      reject("AMOUNT_TOO_LARGE", "amountMinor must be at most " + MAX_AMOUNT_MINOR + " minor units", "amountMinor");
    }
    return { from: from, to: to, amountMinor: obj.amountMinor };
  }

  function idempotencyKey(header) {
    if (header === null || header === undefined) {
      reject("IDEMPOTENCY_KEY_MISSING", "the Idempotency-Key header is required");
    }
    if (!KEY_FORMAT.test(header)) {
      reject("IDEMPOTENCY_KEY_INVALID",
        "Idempotency-Key must be 8 to 64 characters of letters, digits, _ or -");
    }
    return header;
  }

  /* ------------------------------------------------------- cursor (Cursor.java) */

  function encodeCursor(postingId) {
    return btoa("v1:" + postingId).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
  }

  function decodeCursor(raw) {
    var decoded;
    try {
      decoded = atob(raw.replace(/-/g, "+").replace(/_/g, "/"));
    } catch (bad) {
      reject("INVALID_CURSOR", "cursor is not valid", "cursor");
    }
    if (decoded.slice(0, 3) !== "v1:") {
      reject("INVALID_CURSOR", "cursor is not valid", "cursor");
    }
    var id = Number(decoded.slice(3));
    if (!Number.isSafeInteger(id)) {
      reject("INVALID_CURSOR", "cursor is not valid", "cursor");
    }
    return id;
  }

  /* ------------------------------------------------------------ response shapes */

  function renderAccount(account) {
    return {
      id: account.id,
      name: account.name,
      balanceMinor: account.balanceMinor,
      createdAt: account.createdAt
    };
  }

  function renderStatement(accountId, page) {
    return {
      accountId: accountId,
      entries: page.entries.map(function (line) {
        return {
          postingId: line.postingId,
          transferId: line.transferId,
          counterpartyAccountId: line.counterpartyAccountId,
          amountMinor: line.amountMinor,
          balanceAfterMinor: line.balanceAfterMinor,
          createdAt: line.createdAt
        };
      }),
      nextCursor: page.hasMore && page.entries.length > 0
        ? encodeCursor(page.entries[page.entries.length - 1].postingId)
        : null
    };
  }

  // TransfersHandler.render. A replay repeats the original status on purpose and adds one header,
  // so a replayed 201 stays a 201.
  function renderTransfer(outcome, fields) {
    switch (outcome.kind) {
      case "applied":
        return {
          status: 201,
          body: {
            id: outcome.id,
            fromAccountId: fields.from,
            toAccountId: fields.to,
            amountMinor: fields.amountMinor,
            createdAt: outcome.at
          },
          headers: {}
        };
      case "insufficientFunds":
        return errorResult("INSUFFICIENT_FUNDS", "account " + outcome.account + " holds " +
          outcome.balanceMinor + ", the transfer needs " + outcome.requestedMinor);
      case "unknownAccount":
        return errorResult("UNKNOWN_ACCOUNT", "account not found: " + outcome.account);
      case "reservedAccount":
        return errorResult("RESERVED_ACCOUNT", "the world account may not be named in a transfer");
      case "keyConflict":
        return errorResult("IDEMPOTENCY_KEY_CONFLICT", "the idempotency key was reused with a different transfer");
      case "replayed": {
        var replayed = renderTransfer(outcome.first, fields);
        replayed.headers["Idempotency-Replayed"] = "true";
        return replayed;
      }
      default:
        return errorResult("INTERNAL", "unknown outcome");
    }
  }

  function errorResult(code, message, field) {
    var error = { code: code, message: message };
    if (field !== undefined) {
      error.field = field;
    }
    error.requestId = uuid();
    return { status: STATUS[code] || 500, body: { error: error }, headers: {} };
  }

  /* ---------------------------------------------------------------- routing */

  function handle(method, path, url, headers, rawBody) {
    var query = url.searchParams;
    var segments = path.split("?")[0].split("/").filter(function (s) {
      return s.length > 0;
    });

    if (segments[0] === "reconciliation" && segments.length === 1) {
      if (method !== "GET") {
        return errorResult("METHOD_NOT_ALLOWED", "method not allowed");
      }
      return { status: 200, body: reconcile(), headers: {} };
    }

    if (segments[0] === "transfers" && segments.length === 1) {
      if (method !== "POST") {
        return errorResult("METHOD_NOT_ALLOWED", "method not allowed");
      }
      var key = idempotencyKey(headers.get("Idempotency-Key"));
      var fields = transferFields(parseBody(rawBody));
      var outcome = applyTransfer({
        key: key,
        from: fields.from,
        to: fields.to,
        amount: fields.amountMinor
      });
      return renderTransfer(outcome, fields);
    }

    if (segments[0] === "accounts") {
      if (segments.length === 1) {
        if (method === "GET") {
          return {
            status: 200,
            body: {
              accounts: creationOrder.map(function (id) {
                return renderAccount(accounts.get(id));
              })
            },
            headers: {}
          };
        }
        if (method === "POST") {
          var created = accountFields(parseBody(rawBody));
          var account = createAccount(created.name, created.openingBalanceMinor);
          return {
            status: 201,
            body: renderAccount(account),
            headers: { Location: "/accounts/" + account.id }
          };
        }
        return errorResult("METHOD_NOT_ALLOWED", "method not allowed");
      }

      var account = resolve(segments[1]);

      if (segments.length === 2) {
        if (method !== "GET") {
          return errorResult("METHOD_NOT_ALLOWED", "method not allowed");
        }
        return { status: 200, body: renderAccount(account), headers: {} };
      }

      if (segments.length === 3 && segments[2] === "statement") {
        if (method !== "GET") {
          return errorResult("METHOD_NOT_ALLOWED", "method not allowed");
        }
        var limit = parseLimit(query.get("limit"));
        var cursor = query.get("cursor");
        var before = cursor === null ? Number.MAX_SAFE_INTEGER : decodeCursor(cursor);
        return {
          status: 200,
          body: renderStatement(account.id, statement(account.id, before, limit)),
          headers: {}
        };
      }
    }

    return errorResult("NOT_FOUND", "no route for " + method + " " + path);
  }

  // A path id is opaque: not a UUID, or a UUID with no account, is simply not found.
  function resolve(raw) {
    if (!UUID_FORMAT.test(raw) || !accounts.has(raw)) {
      reject("ACCOUNT_NOT_FOUND", "account not found: " + raw);
    }
    return accounts.get(raw);
  }

  // Out of range is refused rather than quietly clamped, because a clamp hides a caller's mistake.
  function parseLimit(raw) {
    if (raw === null) {
      return DEFAULT_LIMIT;
    }
    var limit = Number(raw);
    if (!Number.isInteger(limit) || limit < 1 || limit > MAX_LIMIT) {
      reject("INVALID_LIMIT", "limit must be between 1 and " + MAX_LIMIT, "limit");
    }
    return limit;
  }

  /* ------------------------------------------------------- the fetch interceptor */

  var realFetch = window.fetch ? window.fetch.bind(window) : null;

  // Anything that is not one of these three shapes is handed back to the browser and logged, so a
  // request trying to leave the page would be visible rather than silent.
  function apiPath(pathname) {
    var match = /\/(accounts|transfers|reconciliation)(\/|$)/.exec(pathname);
    return match === null ? null : pathname.slice(match.index);
  }

  function describe(input, init) {
    var options = init || {};
    var raw = input;
    if (typeof Request !== "undefined" && input instanceof Request) {
      raw = input.url;
    } else if (input && typeof input === "object" && typeof input.url === "string") {
      raw = input.url;
    }
    var url = new URL(String(raw), window.location.href);
    var method = String(
      options.method || (typeof Request !== "undefined" && input instanceof Request ? input.method : "GET")
    ).toUpperCase();
    var headers = new Headers(
      options.headers || (typeof Request !== "undefined" && input instanceof Request ? input.headers : undefined)
    );
    return {
      url: url,
      method: method,
      headers: headers,
      body: typeof options.body === "string" ? options.body : null
    };
  }

  function jsonResponse(result) {
    var headers = { "Content-Type": "application/json" };
    Object.keys(result.headers || {}).forEach(function (name) {
      headers[name] = result.headers[name];
    });
    return new Response(JSON.stringify(result.body), { status: result.status, headers: headers });
  }

  // Answering instantly would skip past the client's own loading states, which are built for a real
  // network and are part of what there is to look at.
  function pause() {
    return new Promise(function (resolve) {
      setTimeout(resolve, 60 + Math.random() * 60);
    });
  }

  window.fetch = function (input, init) {
    var request;
    try {
      request = describe(input, init);
    } catch (bad) {
      return realFetch ? realFetch(input, init) : Promise.reject(bad);
    }
    var path = apiPath(request.url.pathname);
    if (path === null) {
      console.warn("[tally] not a Tally path, letting the browser handle it:", request.url.href);
      return realFetch ? realFetch(input, init) : Promise.reject(new Error("no fetch"));
    }
    return pause().then(function () {
      try {
        return jsonResponse(handle(request.method, path, request.url, request.headers, request.body));
      } catch (caught) {
        if (caught && caught.isApiError) {
          return jsonResponse(errorResult(caught.code, caught.message, caught.field));
        }
        console.error("[tally] the stand-in threw", caught);
        return jsonResponse(errorResult("INTERNAL", "the stand-in hit an error"));
      }
    });
  };

  /* --------------------------------------------------------------- opening state */

  // The same two names as the recorded run in ../data/tally-run.json, opened through createAccount so
  // their money comes out of world like any other opening and the book starts at zero.
  createAccount("Amara", 50000);
  createAccount("Ben", 0);

  console.info(
    "[tally] The bank answering this page is a JavaScript copy of Tally's rules, running in your " +
    "browser. It is not the real service. The real one is Java and Postgres: see " +
    "src/main/java/dev/tally in the repository."
  );

  // Tells the page framing this app to drop its placeholder, once the client is really up.
  window.addEventListener("load", function () {
    if (window.parent !== window) {
      try {
        window.parent.postMessage({ tally: "ready" }, "*");
      } catch (blocked) {
        // A stricter browser refusing this changes nothing about the app itself.
      }
    }
  });
})();
