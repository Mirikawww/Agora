/**
 * Agora update-check Worker
 *
 * Proxies GitHub Releases for a *private* repository so the Android app never
 * embeds a GitHub token. The token lives only in Cloudflare secrets.
 *
 * Endpoints:
 *   GET /           -> 200 + latest release JSON (or 204 if no releases)
 *   GET /latest     -> same as /
 *   GET /health     -> 200 {"ok":true}
 *   GET /download   -> stream the first .apk asset from the latest release
 *
 * Response JSON (stable, app-facing):
 * {
 *   "tag_name": "v1.3.8",
 *   "version": "1.3.8",
 *   "html_url": "https://github.com/owner/repo/releases/tag/v1.3.8",
 *   "body": "release notes markdown",
 *   "published_at": "2026-01-01T00:00:00Z",
 *   "has_apk": true,
 *   "download_url": "https://this-worker/download"
 * }
 */

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, HEAD, OPTIONS",
  "Access-Control-Allow-Headers": "Accept, Content-Type",
};

export default {
  async fetch(request, env, ctx) {
    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: CORS });
    }

    if (request.method !== "GET" && request.method !== "HEAD") {
      return json({ error: "method_not_allowed" }, 405);
    }

    const url = new URL(request.url);
    const path = url.pathname.replace(/\/+$/, "") || "/";

    try {
      if (path === "/health") {
        return json({ ok: true });
      }

      if (
        !env.GITHUB_OWNER ||
        env.GITHUB_OWNER === "CHANGE_ME_OWNER" ||
        !env.GITHUB_REPO ||
        env.GITHUB_REPO === "CHANGE_ME_REPO"
      ) {
        return json(
          {
            error: "misconfigured",
            message: "Set GITHUB_OWNER and GITHUB_REPO in wrangler.toml",
          },
          500,
        );
      }
      if (!env.GITHUB_TOKEN) {
        return json(
          { error: "misconfigured", message: "Missing GITHUB_TOKEN secret" },
          500,
        );
      }

      if (path === "/" || path === "/latest") {
        return await handleLatest(env, ctx, url.origin);
      }

      if (path === "/download") {
        return await handleDownload(env, ctx, request);
      }

      return json({ error: "not_found" }, 404);
    } catch (err) {
      return json(
        {
          error: "internal",
          message: String(err && err.message ? err.message : err),
        },
        500,
      );
    }
  },
};

async function handleLatest(env, ctx, origin) {
  const release = await fetchLatestRelease(env, ctx);
  if (!release) {
    return new Response(null, {
      status: 204,
      headers: {
        ...CORS,
        "Cache-Control": "public, max-age=60",
      },
    });
  }

  const payload = shapeRelease(release, env, origin);
  return json(payload, 200, {
    // Short edge cache: clients still revalidate often enough for releases.
    "Cache-Control": "public, max-age=120, s-maxage=300",
  });
}

async function handleDownload(env, ctx, request) {
  const release = await fetchLatestRelease(env, ctx);
  if (!release) {
    return json({ error: "no_release" }, 404);
  }

  const asset = pickApkAsset(release.assets || []);
  if (!asset) {
    return json(
      { error: "no_apk_asset", message: "Latest release has no .apk asset" },
      404,
    );
  }

  // Private assets require the token; stream through the Worker so the app /
  // browser never sees the token.
  const upstream = await fetch(asset.url, {
    headers: githubHeaders(env, "application/octet-stream"),
    redirect: "follow",
  });

  if (!upstream.ok) {
    return json(
      {
        error: "asset_fetch_failed",
        status: upstream.status,
      },
      502,
    );
  }

  const headers = new Headers(CORS);
  headers.set(
    "Content-Type",
    asset.content_type || "application/vnd.android.package-archive",
  );
  headers.set(
    "Content-Disposition",
    `attachment; filename="${sanitizeFilename(asset.name || "agora.apk")}"`,
  );
  if (upstream.headers.get("content-length")) {
    headers.set("Content-Length", upstream.headers.get("content-length"));
  }
  headers.set("Cache-Control", "private, max-age=60");

  if (request.method === "HEAD") {
    return new Response(null, { status: 200, headers });
  }

  return new Response(upstream.body, { status: 200, headers });
}

async function fetchLatestRelease(env, ctx) {
  const apiUrl =
    `https://api.github.com/repos/${env.GITHUB_OWNER}/${env.GITHUB_REPO}/releases/latest`;

  // Prefer Cloudflare Cache API for the GitHub response (token stays server-side).
  const cache = caches.default;
  const cacheKey = new Request(`https://update-check.internal/latest/${env.GITHUB_OWNER}/${env.GITHUB_REPO}`, {
    method: "GET",
  });
  const cached = await cache.match(cacheKey);
  if (cached && cached.ok) {
    return cached.json();
  }

  const res = await fetch(apiUrl, {
    headers: githubHeaders(env, "application/vnd.github+json"),
  });

  if (res.status === 404) {
    // No releases yet, or repo not found / no access.
    return null;
  }
  if (!res.ok) {
    const text = await res.text();
    throw new Error(`GitHub API ${res.status}: ${text.slice(0, 200)}`);
  }

  const data = await res.json();
  const toCache = new Response(JSON.stringify(data), {
    status: 200,
    headers: {
      "Content-Type": "application/json",
      "Cache-Control": "public, max-age=120",
    },
  });
  ctx.waitUntil(cache.put(cacheKey, toCache));
  return data;
}

function shapeRelease(release, env, origin) {
  const tag = String(release.tag_name || "");
  const version = tag.replace(/^v/i, "");
  const hasApk = !!pickApkAsset(release.assets || []);

  return {
    // GitHub-compatible fields (UpdateChecker already understands these)
    tag_name: tag,
    html_url:
      release.html_url ||
      `https://github.com/${env.GITHUB_OWNER}/${env.GITHUB_REPO}/releases/tag/${encodeURIComponent(tag)}`,
    body: release.body || "",
    // Extra convenience fields
    version,
    published_at: release.published_at || null,
    has_apk: hasApk,
    // Absolute Worker URL so private APK assets can be downloaded without a token in the app.
    download_url: hasApk ? `${origin}/download` : null,
  };
}

function pickApkAsset(assets) {
  const list = Array.isArray(assets) ? assets : [];
  const scored = list
    .filter(
      (a) =>
        a &&
        typeof a.name === "string" &&
        a.name.toLowerCase().endsWith(".apk") &&
        a.url,
    )
    .map((a) => {
      const n = a.name.toLowerCase();
      let score = 0;
      if (n.includes("release")) score += 3;
      if (n.includes("fdroid")) score += 2;
      if (n.includes("universal") || n.includes("arm64")) score += 1;
      if (n.includes("debug")) score -= 5;
      return { a, score };
    })
    .sort((x, y) => y.score - x.score);
  return scored[0]?.a || null;
}

function githubHeaders(env, accept) {
  return {
    Accept: accept,
    Authorization: `Bearer ${env.GITHUB_TOKEN}`,
    "User-Agent": "Agora-Update-Check-Worker",
    "X-GitHub-Api-Version": "2022-11-28",
  };
}

function sanitizeFilename(name) {
  return (
    String(name)
      .replace(/[^\w.\-()+ ]+/g, "_")
      .slice(0, 180) || "agora.apk"
  );
}

function json(data, status = 200, extraHeaders = {}) {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      ...CORS,
      ...extraHeaders,
    },
  });
}
