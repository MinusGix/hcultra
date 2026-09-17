/*
 * Message renderer.
 *
 * The markdown/KaTeX/highlight pipeline below is configured to match
 * hack.chat's own client (hc/client/client.js) as closely as possible, so a
 * message renders here the way it renders on the site. When the site changes
 * its options, change them here too — that parity is the whole reason this is a
 * WebView rather than a native text renderer.
 *
 * Native pushes state in; this file owns the DOM and the scrolling.
 */
(function () {
  'use strict';

  // --- markdown engine, mirroring hc/client/client.js ---------------------
  var markdownOptions = {
    html: false,           // never render raw HTML from a message
    xhtmlOut: false,
    breaks: true,
    langPrefix: '',
    linkify: true,
    typographer: true,
    quotes: '""\'\'',

    doHighlight: true,
    highlight: function (str, lang) {
      if (!markdownOptions.doHighlight || !window.hljs) return '';
      if (lang && hljs.getLanguage(lang)) {
        // hljs 9.x signature: highlight(lang, code)
        try { return hljs.highlight(lang, str).value; } catch (e) {}
      }
      try { return hljs.highlightAuto(str).value; } catch (e) {}
      return '';
    }
  };

  var md = new Remarkable('full', markdownOptions);

  function esc(s) { return Remarkable.utils.escapeHtml(String(s == null ? '' : s)); }

  // Images are links unless the user opts in, matching the site's default.
  //
  // The same list lives in core as `ImageHosts`, which is what the WebView's
  // request interceptor enforces; this copy only decides whether an <img> is
  // written at all. Change both together.
  var allowImages = false;
  var imgHostWhitelist = [
    'i.imgur.com', 'imgur.com', 'share.lyka.pro', 'cdn.discordapp.com',
    'i.gyazo.com', 'i.postimg.cc', 'i.ytimg.com', 'i.ibb.co'
  ];

  function hostOf(link) {
    try { return new URL(link, 'https://hack.chat').hostname; } catch (e) { return ''; }
  }

  md.renderer.rules.image = function (tokens, idx, options) {
    var src = esc(tokens[idx].src);
    if (allowImages && imgHostWhitelist.indexOf(hostOf(tokens[idx].src)) !== -1) {
      var alt = tokens[idx].alt ? esc(Remarkable.utils.replaceEntities(Remarkable.utils.unescapeMd(tokens[idx].alt))) : '';
      return '<a href="' + src + '" data-ext="1"><img src="' + src + '" alt="' + alt + '" referrerpolicy="no-referrer"></a>';
    }
    return '<a href="' + src + '" data-ext="1">' + esc(Remarkable.utils.replaceEntities(tokens[idx].src)) + '</a>';
  };

  // Links are handed to native rather than navigated: the WebView must never
  // leave the renderer document.
  md.renderer.rules.link_open = function (tokens, idx) {
    return '<a data-ext="1" href="' + esc(tokens[idx].href) + '">';
  };

  // Escape first, then linkify ?channel references — same order as the site.
  md.renderer.rules.text = function (tokens, idx) {
    var content = esc(tokens[idx].content);
    if (content.indexOf('?') !== -1) {
      content = content.replace(/(^|\s)(\?)\S+?(?=[,.!?:)]?\s|$)/gm, function (match) {
        var name = esc(Remarkable.utils.replaceEntities(match.trim()));
        var lead = match[0] !== '?' ? match[0] : '';
        return lead + '<a data-chan="' + name.slice(1) + '" href="#">' + name + '</a>';
      });
    }
    return content;
  };

  md.use(remarkableKatex);

  // KaTeX can be turned off, as on the site (some users dislike $ being magic).
  var katexEnabled = true;
  function setKatex(on) {
    katexEnabled = !!on;
    try {
      if (katexEnabled) {
        md.inline.ruler.enable(['katex']);
        md.block.ruler.enable(['katex']);
      } else {
        md.inline.ruler.disable(['katex']);
        md.block.ruler.disable(['katex']);
      }
    } catch (e) {}
  }

  function renderBody(text) {
    try {
      return md.render(String(text == null ? '' : text));
    } catch (e) {
      // A malformed expression must not blank the message.
      return '<p>' + esc(text) + '</p>';
    }
  }

  // --- DOM -----------------------------------------------------------------
  /*
   * One container per channel, exactly one of them visible.
   *
   * Native keeps a matching record of what each container holds and sends only
   * what changed (see TranscriptSync), so switching channels is a `hidden`
   * toggle: nothing re-parses, images stay decoded where they are, and each
   * channel keeps the place you were reading it at.
   *
   * It used to be a single container keyed by message id, which collided across
   * channels — every id starts at 1 in its own — so a swap rewrote every row
   * whose text happened to differ. That was 250 rows of markdown, KaTeX and
   * highlight.js, and every image destroyed and rebuilt, for one tab tap.
   */
  var logRoot = document.getElementById('log');
  var channels = Object.create(null);  // name -> {el, nodes, scrollY, pinned}
  var current = null;

  function channelState(name) {
    var c = channels[name];
    if (!c) {
      var el = document.createElement('div');
      el.className = 'channel';
      el.hidden = true;
      logRoot.appendChild(el);
      c = channels[name] = {
        el: el,
        nodes: Object.create(null),  // localId -> element
        scrollY: 0,
        pinned: true,                // stick to bottom unless the reader scrolls up
      };
    }
    return c;
  }

  function atBottom() {
    return (window.innerHeight + window.scrollY) >= (document.body.scrollHeight - 40);
  }

  window.addEventListener('scroll', function () {
    var c = channels[current];
    if (!c) return;
    var b = atBottom();
    if (b !== c.pinned) {
      c.pinned = b;
      post('onPinnedChanged', String(b));
    }
  }, { passive: true });

  // An image finishes loading well after the message holding it was inserted,
  // and it grows the page as it lands. Without this the transcript slides out
  // from under a reader who was sitting at the bottom — which is where the
  // reader of a chat log normally is. Capture phase: `load` does not bubble.
  document.addEventListener('load', function (e) {
    var c = channels[current];
    if (c && c.pinned && e.target && e.target.tagName === 'IMG') scrollToBottom();
  }, true);

  function post(fn, arg) {
    if (window.HcBridge && window.HcBridge[fn]) {
      try { window.HcBridge[fn](arg); } catch (e) {}
    }
  }

  function scrollToBottom() {
    window.scrollTo(0, document.body.scrollHeight);
  }

  function build(m) {
    var row = document.createElement('div');
    row.setAttribute('data-id', m.localId);
    fill(row, m);
    return row;
  }

  /*
   * Markup deliberately mirrors hack.chat's own DOM — .message / .nick / .trip
   * / .text, with .admin/.mod/.me/.info/.warn modifiers — so the site's 43
   * scheme stylesheets style this renderer without modification.
   */
  function fill(row, m) {
    var cls = ['message'];
    if (m.isMine) cls.push('me');
    if (m.level >= 9999999) cls.push('admin');
    else if (m.level >= 999999) cls.push('mod');
    if (m.kind === 'Info' || m.kind === 'Join' || m.kind === 'Leave') cls.push('info');
    if (m.kind === 'Warning') cls.push('warn');
    cls.push('kind-' + String(m.kind).toLowerCase());
    row.className = cls.join(' ');

    /*
     * Flair and trip share the .trip span, in the site's own order — see
     * client.js: flair alone, trip alone, or "flair trip". Flair is whatever
     * the server says (forceflair allows any string up to 2 chars), so it is
     * escaped and rendered as text rather than mapped to an icon.
     */
    function tripSpan(m) {
      var parts = [];
      if (m.flair) parts.push(esc(m.flair));
      if (m.trip) parts.push(esc(m.trip));
      if (!parts.length) return '';
      return '<span class="trip">' + parts.join(' ') + '</span>';
    }

    /*
     * Tapping a nick mentions it in the composer, so every nick the transcript
     * shows is marked with the nick it stands for. An attribute rather than the
     * element's text: the .nick span also carries the trip and flair, which are
     * not part of the name.
     */
    function nickAttr(m) { return ' data-nick="' + esc(m.nick) + '"'; }

    var head = '';
    if (m.kind === 'Chat') {
      // An explicit per-user colour from the server overrides the scheme's
      // .nick colour, matching how the site treats /changecolor.
      var style = m.color ? ' style="color:#' + esc(m.color).replace(/[^0-9a-fA-F]/g, '') + '"' : '';
      head = '<span class="nick"' + nickAttr(m) + style + '>' + tripSpan(m) + esc(m.nick) + '</span>';
    } else if (m.kind === 'Emote') {
      head = '<span class="nick">*</span>';
    } else if (m.kind === 'Whisper') {
      // Incoming: who it came from.
      head = '<span class="wtag">whisper from</span> <span class="nick"' + nickAttr(m) + '>' +
        esc(m.nick) + '</span>';
    } else if (m.kind === 'WhisperSent') {
      // Outgoing: the server echoes our own whisper back to us with the same
      // shape, so it needs distinguishing or it reads as if they sent it.
      head = '<span class="wtag">whisper to</span> <span class="nick"' + nickAttr(m) + '>' +
        esc(m.nick) + '</span>';
    }

    // Always wrapped, even when empty, so every layout has one element to size
    // and align against. Without it the gutter layout would have to cope with
    // .nick sometimes being absent (info, join, leave) and sometimes being
    // preceded by a .wtag (whispers).
    head = '<span class="head">' + head + '</span> ';

    var flag = '';
    if (m.delivery === 'Sending') flag = '<span class="flag pending">sending\u2026</span>';
    else if (m.delivery === 'Failed') flag = '<span class="flag failed">failed</span>';
    // Deliberately not "failed": it may well have been delivered, and we cannot
    // tell, so the label must not push the user into a duplicate resend.
    else if (m.delivery === 'Unconfirmed') flag = '<span class="flag unconfirmed">unconfirmed</span>';
    // No "still typing" marker for a message being streamed by a bot. Nothing
    // obliges a bot to close the stream \u2014 the reference client discards the
    // text of a `complete` frame, so many never send one \u2014 and a marker that
    // can outlive the thing it describes is worse than no marker: the text
    // arriving is already the visible signal that more is coming.

    /*
     * Join and leave carry the nick structurally and no text at all, so the
     * line is composed here — as client.js does ("nick joined"/"nick left").
     * Escaped rather than run through renderBody: a nick is not markdown, and
     * one containing * or _ must not come out italicised.
     */
    var body;
    if (m.kind === 'Join' || m.kind === 'Leave') {
      body = '<span' + nickAttr(m) + '>' + esc(m.nick) + '</span>' +
        (m.kind === 'Join' ? ' joined' : ' left');
    } else {
      body = renderBody(m.text);
    }

    row.innerHTML = head + '<span class="text">' + body + '</span>' + flag;
  }

  /*
   * Bring one channel's container in line with a patch from native.
   *
   * `order` is every id in transcript order; `upsert` carries bodies only for
   * rows that are new or have changed. Everything else is left exactly as it
   * is — which is the point: an arriving message touches one row, and a channel
   * switch usually touches none.
   *
   * Native is the authority on what changed. The page deliberately keeps no
   * signature of its own: two sides guessing at the same question is how they
   * come to disagree.
   */
  function applyPatch(name, patch) {
    var c = channelState(name);
    if (patch.full) {
      c.el.innerHTML = '';
      c.nodes = Object.create(null);
    }

    var isCurrent = name === current;
    // Only the visible container can be measured; a background one keeps the
    // flag it had, which is what puts a reader back where they were.
    var wasPinned = isCurrent ? (c.pinned || atBottom()) : c.pinned;

    var bodies = Object.create(null);
    var upsert = patch.upsert || [];
    for (var i = 0; i < upsert.length; i++) {
      bodies[String(upsert[i].localId)] = upsert[i];
    }

    var order = patch.order || [];
    var seen = Object.create(null);
    var prev = null;

    for (var j = 0; j < order.length; j++) {
      var id = String(order[j]);
      seen[id] = true;
      var m = bodies[id];
      var el = c.nodes[id];

      if (!el) {
        // Native sends a body for anything we are not already holding, so this
        // only trips if the two have drifted. Skipping beats an empty row.
        if (!m) continue;
        el = build(m);
        c.nodes[id] = el;
      } else if (m) {
        fill(el, m);
      }

      // Put it where `order` says. insertBefore(el, null) appends, and a node
      // already in place is left alone rather than moved through the DOM.
      var want = prev ? prev.nextSibling : c.el.firstChild;
      if (el !== want) c.el.insertBefore(el, want);
      prev = el;
    }

    for (var key in c.nodes) {
      if (!seen[key]) {
        var gone = c.nodes[key];
        if (gone.parentNode) gone.parentNode.removeChild(gone);
        delete c.nodes[key];
      }
    }

    if (isCurrent && wasPinned) scrollToBottom();
  }

  document.addEventListener('click', function (e) {
    if (!e.target.closest) return;
    var a = e.target.closest('a');
    if (a) {
      e.preventDefault();
      if (a.hasAttribute('data-chan')) post('onChannelTap', a.getAttribute('data-chan'));
      else if (a.getAttribute('href')) post('onLinkTap', a.getAttribute('href'));
      return;
    }
    // A nick anywhere in the transcript — the head of a message, or the name in
    // a join/leave line. Checked after links so a nick inside link text (which
    // cannot happen today) would still navigate.
    var n = e.target.closest('[data-nick]');
    if (n) {
      e.preventDefault();
      post('onNickTap', n.getAttribute('data-nick'));
    }
  });

  function setHref(id, href) {
    var el = document.getElementById(id);
    if (el && el.getAttribute('href') !== href) el.setAttribute('href', href);
  }

  // --- native API ----------------------------------------------------------
  window.HC = {
    /** Apply native's patch to one channel, visible or not. */
    apply: function (channel, json) {
      var patch;
      try { patch = JSON.parse(json); } catch (e) { return; }
      applyPatch(String(channel), patch);
    },

    /*
     * Bring a channel to the front.
     *
     * The whole saving: no rendering happens here, only two `hidden` flags and
     * a scroll position. Everything this channel had drawn is still drawn.
     */
    show: function (channel) {
      channel = String(channel);
      var next = channelState(channel);
      if (current === channel) return;
      var previous = channels[current];
      if (previous) {
        // Read before hiding: once it is hidden the document collapses to the
        // next container's height and both of these measure that instead.
        previous.pinned = previous.pinned || atBottom();
        previous.scrollY = window.scrollY;
        previous.el.hidden = true;
      }
      current = channel;
      next.el.hidden = false;
      // A reader who was at the bottom wants the bottom, which is not the same
      // offset it was: the channel they are arriving at is a different length.
      if (next.pinned) scrollToBottom();
      else window.scrollTo(0, next.scrollY);
    },

    /** Drop a channel's DOM. Native evicts to keep retained transcripts bounded. */
    evict: function (channel) {
      channel = String(channel);
      // Never the one on screen; native does not ask, and this is the cheap
      // guard that keeps a bug there from blanking the transcript.
      if (channel === current) return;
      var c = channels[channel];
      if (!c) return;
      if (c.el.parentNode) c.el.parentNode.removeChild(c.el);
      delete channels[channel];
    },

    setKatex: setKatex,
    /** One class on <body>; the three layouts are pure CSS over stable markup. */
    setLayout: function (cssClass) {
      var c = channels[current];
      var wasPinned = c ? (c.pinned || atBottom()) : true;
      document.body.className = String(cssClass || 'layout-inline');
      if (wasPinned) scrollToBottom();
    },
    /*
     * The transcript's text size, as a multiplier on the stylesheet's base.
     *
     * Written to the root element and nowhere else: app.css sizes everything
     * else in `em`, so one number moves the padding and the gutter along with
     * the letters. `calc` over the stylesheet's own `--hc-base` rather than a
     * pixel count computed here, so the base stays a single fact living in one
     * file.
     *
     * Growing the text moves everything below the fold further below it, which
     * for a chat log means away from the newest message. So a reader sitting at
     * the bottom is put back there afterwards — the same courtesy an arriving
     * image gets, and for the same reason.
     */
    setFontScale: function (scale) {
      scale = Number(scale);
      if (!isFinite(scale) || scale <= 0) return;
      var c = channels[current];
      var wasPinned = c ? (c.pinned || atBottom()) : true;
      document.documentElement.style.fontSize = 'calc(var(--hc-base) * ' + scale + ')';
      if (wasPinned) scrollToBottom();
    },
    setTheme: function (scheme, hljsTheme) {
      if (scheme) setHref('scheme', 'schemes/' + scheme + '.css');
      if (hljsTheme) setHref('hljs-theme', 'vendor/hljs/styles/' + hljsTheme + '.min.css');
    },
    /*
     * Whether whitelisted images embed or stay links.
     *
     * Rebuilds what is already on screen, because the render diff keys on the
     * message alone: without this, turning images on would only affect messages
     * that arrived afterwards, and turning them off would leave the ones
     * already showing.
     */
    setAllowImages: function (on) {
      on = !!on;
      if (on === allowImages) return;
      allowImages = on;
      // Whether a message shows an image is not part of the message, so every
      // container is now stale — including the ones nobody is looking at.
      // Dropped rather than redrawn here: native resets its own record next to
      // this call and pushes the visible channel back in full, which keeps one
      // side authoritative rather than two agreeing by luck.
      for (var name in channels) {
        var c = channels[name];
        if (c.el.parentNode) c.el.parentNode.removeChild(c.el);
      }
      channels = Object.create(null);
      current = null;
    },
    scrollToBottom: function () {
      var c = channels[current];
      if (c) c.pinned = true;
      scrollToBottom();
    }
  };

  post('onReady', '');
})();
