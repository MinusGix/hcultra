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
  var log = document.getElementById('log');
  var nodes = Object.create(null);   // localId -> {el, sig}
  var pinned = true;                 // stick to bottom unless the user scrolls up
  var shown = [];                    // last snapshot, for a settings-driven rebuild

  function atBottom() {
    return (window.innerHeight + window.scrollY) >= (document.body.scrollHeight - 40);
  }

  window.addEventListener('scroll', function () {
    var b = atBottom();
    if (b !== pinned) {
      pinned = b;
      post('onPinnedChanged', String(b));
    }
  }, { passive: true });

  // An image finishes loading well after the message holding it was inserted,
  // and it grows the page as it lands. Without this the transcript slides out
  // from under a reader who was sitting at the bottom — which is where the
  // reader of a chat log normally is. Capture phase: `load` does not bubble.
  document.addEventListener('load', function (e) {
    if (pinned && e.target && e.target.tagName === 'IMG') scrollToBottom();
  }, true);

  function post(fn, arg) {
    if (window.HcBridge && window.HcBridge[fn]) {
      try { window.HcBridge[fn](arg); } catch (e) {}
    }
  }

  function scrollToBottom() {
    window.scrollTo(0, document.body.scrollHeight);
  }

  // Signature of everything that affects rendering, so an unchanged message is
  // never re-rendered (re-running KaTeX on every frame is expensive).
  function signature(m) {
    return [m.text, m.delivery, m.nick, m.kind, m.color, m.level,
            m.trip, m.flair].join(' ');
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

  // Full-snapshot diff. The buffer is bounded (a few hundred), so this stays
  // cheap, and it means native never has to track what the DOM already has.
  function apply(messages) {
    var wasPinned = pinned || atBottom();
    shown = messages;
    var seen = Object.create(null);
    var prev = null;

    for (var i = 0; i < messages.length; i++) {
      var m = messages[i];
      var id = String(m.localId);
      seen[id] = true;
      var entry = nodes[id];
      var sig = signature(m);

      if (!entry) {
        var el = build(m);
        // Insert in order rather than always appending: an edited older
        // message must not jump to the end.
        if (prev && prev.nextSibling) log.insertBefore(el, prev.nextSibling);
        else log.appendChild(el);
        nodes[id] = { el: el, sig: sig };
        prev = el;
      } else {
        if (entry.sig !== sig) {
          fill(entry.el, m);
          entry.sig = sig;
        }
        prev = entry.el;
      }
    }

    for (var key in nodes) {
      if (!seen[key]) {
        if (nodes[key].el.parentNode) nodes[key].el.parentNode.removeChild(nodes[key].el);
        delete nodes[key];
      }
    }

    if (wasPinned) scrollToBottom();
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
    render: function (json) {
      var msgs;
      try { msgs = JSON.parse(json); } catch (e) { return; }
      apply(msgs);
    },
    clear: function () {
      log.innerHTML = '';
      nodes = Object.create(null);
      shown = [];
    },
    setKatex: setKatex,
    /** One class on <body>; the three layouts are pure CSS over stable markup. */
    setLayout: function (cssClass) {
      var wasPinned = pinned || atBottom();
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
      var wasPinned = pinned || atBottom();
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
      var messages = shown;
      HC.clear();
      apply(messages);
    },
    scrollToBottom: function () { pinned = true; scrollToBottom(); }
  };

  post('onReady', '');
})();
