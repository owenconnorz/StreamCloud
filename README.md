<div align="center">

<a href="https://discord.gg/z62jDev7t3">
  <img src="https://img.shields.io/discord/1240015357700214805?color=5865F2&label=Join%20Our%20Discord&logo=discord&style=for-the-badge" alt="Discord">
</a>

<br><br>

<h1>🎵 StreamCloud 🎬</h1>

<p>
  <b>Android Music • Movies • TV • Plugins</b>
</p>

<p>
  Native Android music & media streaming app built with Kotlin and Jetpack Compose.
</p>

</div>

<hr>

<h2>✨ Features</h2>

<h3>🎵 Music</h3>
<ul>
  <li>YouTube Music streaming with multi-client Innertube waterfall resolution</li>
  <li>🎤 Lyrics display</li>
  <li>💾 Offline downloads with ExoPlayer cache</li>
  <li>🎚️ Equalizer / audio effects</li>
</ul>

<h3>📺 Movies & TV</h3>
<ul>
  <li>🎬 Movie & TV playback</li>
  <li>📡 Chromecast support</li>
  <li>🔊 Sonos speaker support</li>
  <li>🧲 Torrent streaming via TorrServer</li>
</ul>

<h3>🔌 Plugins</h3>
<ul>
  <li>CloudStream support</li>
  <li>Nuvio support</li>
  <li>Stremio support</li>
</ul>

<h3>🚗 Android</h3>
<ul>
  <li>Android Auto support</li>
  <li>Android Automotive OS support</li>
  <li>Full media browse tree support</li>
</ul>

<h3>🤖 Extras</h3>
<ul>
  <li>AI Assistant screen</li>
</ul>

<hr>

<h2>🛠️ Tech Stack</h2>

<table>
  <tr>
    <th>Component</th>
    <th>Technology</th>
  </tr>
  <tr>
    <td>💜 Language</td>
    <td>Kotlin</td>
  </tr>
  <tr>
    <td>🎨 UI</td>
    <td>Jetpack Compose</td>
  </tr>
  <tr>
    <td>▶️ Player</td>
    <td>Media3 / ExoPlayer</td>
  </tr>
  <tr>
    <td>🌐 Networking</td>
    <td>OkHttp</td>
  </tr>
  <tr>
    <td>🗄️ Local DB</td>
    <td>Room</td>
  </tr>
  <tr>
    <td>⚡ Architecture</td>
    <td>ServiceLocator + Coroutines + Flow</td>
  </tr>
</table>

<hr>

<h2>🎧 Stream Resolution</h2>

<p>Audio streams are resolved via a multi-client Innertube waterfall:</p>

<ol>
  <li><code>ANDROID_MUSIC</code> 🎵 — YT Music client, best audio metadata</li>
  <li><code>ANDROID</code> 📱 — Standard Android client</li>
  <li><code>ANDROID_TESTSUITE</code> 🧪 — Internal test client</li>
  <li><code>ANDROID_VR</code> 🥽 — Oculus Quest clients</li>
  <li><code>IOS</code> 🍎 — iPhone client</li>
  <li><code>IPADOS</code> 📱 — iPad client</li>
  <li><code>ANDROID_CREATOR</code> 🎬 — YouTube Studio client</li>
</ol>

<p>
All clients return plain stream URLs. No cipher deobfuscation required.
</p>

<hr>

<h2>🚀 Build</h2>

<pre><code>./gradlew assembleDebug</code></pre>

<h3>📋 Requirements</h3>

<ul>
  <li>Android Studio Hedgehog or newer</li>
  <li>Min SDK: 26</li>
  <li>Target SDK: 35</li>
</ul>

<hr>

<div align="center">

<h2>💬 Community</h2>

<p>
Join our Discord for support, feature requests and updates.
</p>

<a href="https://discord.gg/z62jDev7t3">
  <img src="https://img.shields.io/discord/1240015357700214805?color=5865F2&label=Discord%20Community&logo=discord&style=for-the-badge" alt="Discord">
</a>

</div>