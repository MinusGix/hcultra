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
    return [m.text, m.delivery, m.nick, m.streamComplete, m.kind, m.color, m.level].join(' ');
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

    var head = '';
    if (m.kind === 'Chat') {
      var trip = m.trip ? '<span class="trip">' + esc(m.trip) + '</span>' : '';
      // An explicit per-user colour from the server overrides the scheme's
      // .nick colour, matching how the site treats /changecolor.
      var style = m.color ? ' style="color:#' + esc(m.color).replace(/[^0-9a-fA-F]/g, '') + '"' : '';
      head = '<span class="nick"' + style + '>' + trip + esc(m.nick) + '</span> ';
    } else if (m.kind === 'Emote') {
      head = '<span class="nick">*</span> ';
    } else if (m.kind === 'Whisper') {
      // Incoming: who it came from.
      head = '<span class="wtag">whisper from</span> <span class="nick">' +
        esc(m.nick) + '</span> ';
    } else if (m.kind === 'WhisperSent') {
      // Outgoing: the server echoes our own whisper back to us with the same
      // shape, so it needs distinguishing or it reads as if they sent it.
      head = '<span class="wtag">whisper to</span> <span class="nick">' +
        esc(m.nick) + '</span> ';
    }

    var flag = '';
    if (m.delivery === 'Sending') flag = '<span class="flag pending">sending\u2026</span>';
    else if (m.delivery === 'Failed') flag = '<span class="flag failed">failed</span>';
    if (m.streamComplete === false) flag += '<span class="flag streaming">\u2026</span>';

    row.innerHTML = head + '<span class="text">' + renderBody(m.text) + '</span>' + flag;
  }

  // Full-snapshot diff. The buffer is bounded (a few hundred), so this stays
  // cheap, and it means native never has to track what the DOM already has.
  function apply(messages) {
    var wasPinned = pinned || atBottom();
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
    var a = e.target.closest && e.target.closest('a');
    if (!a) return;
    e.preventDefault();
    if (a.hasAttribute('data-chan')) post('onChannelTap', a.getAttribute('data-chan'));
    else if (a.getAttribute('href')) post('onLinkTap', a.getAttribute('href'));
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
    },
    setKatex: setKatex,
    setTheme: function (scheme, hljsTheme) {
      if (scheme) setHref('scheme', 'schemes/' + scheme + '.css');
      if (hljsTheme) setHref('hljs-theme', 'vendor/hljs/styles/' + hljsTheme + '.min.css');
    },
    setAllowImages: function (on) { allowImages = !!on; },
    scrollToBottom: function () { pinned = true; scrollToBottom(); }
  };

  post('onReady', '');
})();
