/**
 * Agora update-check Worker
 *
 * Proxies GitHub Releases for a *private* repository so the Android app never
 * embeds a GitHub token. The token lives only in Cloudflare secrets.
 *
 * Endpoints:
 *   GET /           -> 200 + latest release JSON (or 204 if no releases)
 *   GET /latest     -> same as /
 *   GET /releases   -> 200 + array of historical releases (newest first)
 *   GET /health     -> 200 {"ok":true}
 *   GET /download?abi=arm64-v8a  -> stream preferred .apk for device ABI
 *                                   (fallback: universal, then any release apk)
 *
 * Response JSON (stable, app-facing) for /latest:
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

      if (path === "/releases") {
        return await handleReleases(env, ctx);
      }

      if (path === "/download") {
        return await handleDownload(env, ctx, request, url);
      }

      // ── CI channel ────────────────────────────────────────────────
      // Serves the newest successful Actions build straight from its artifact,
      // so nightly builds never have to be published as (pre)releases.
      if (path === "/ci/latest") {
        return await handleCiLatest(env, url.origin);
      }

      if (path === "/ci/download") {
        return await handleCiDownload(env);
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

/** Historical release notes (newest first). App Settings → About → Changelog. */
async function handleReleases(env, ctx) {
  const releases = await fetchAllReleases(env, ctx);
  const payload = releases.map((r) => {
    const tag = String(r.tag_name || "");
    const version = tag.replace(/^v/i, "");
    return {
      tag_name: tag,
      version,
      html_url: r.html_url || "",
      body: r.body || "",
      published_at: r.published_at || null,
    };
  });
  return json(payload, 200, {
    "Cache-Control": "public, max-age=120, s-maxage=300",
  });
}

async function handleDownload(env, ctx, request, url) {
  const release = await fetchLatestRelease(env, ctx);
  if (!release) {
    return json({ error: "no_release" }, 404);
  }

  // Prefer device ABI APK (e.g. arm64-v8a), then universal, then any release apk.
  const abi = (url.searchParams.get("abi") || "").trim().toLowerCase();
  const asset = pickApkAsset(release.assets || [], abi);
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
  headers.set("X-Agora-Apk-Name", asset.name || "");

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

async function fetchAllReleases(env, ctx) {
  const apiUrl =
    `https://api.github.com/repos/${env.GITHUB_OWNER}/${env.GITHUB_REPO}/releases?per_page=50`;

  const cache = caches.default;
  const cacheKey = new Request(
    `https://update-check.internal/releases/${env.GITHUB_OWNER}/${env.GITHUB_REPO}`,
    { method: "GET" },
  );
  const cached = await cache.match(cacheKey);
  if (cached && cached.ok) {
    return cached.json();
  }

  const res = await fetch(apiUrl, {
    headers: githubHeaders(env, "application/vnd.github+json"),
  });
  if (res.status === 404) return [];
  if (!res.ok) {
    const text = await res.text();
    throw new Error(`GitHub API ${res.status}: ${text.slice(0, 200)}`);
  }
  const data = await res.json();
  const list = Array.isArray(data) ? data : [];
  const toCache = new Response(JSON.stringify(list), {
    status: 200,
    headers: {
      "Content-Type": "application/json",
      "Cache-Control": "public, max-age=120",
    },
  });
  ctx.waitUntil(cache.put(cacheKey, toCache));
  return list;
}

function shapeRelease(release, env, origin) {
  const tag = String(release.tag_name || "");
  const version = tag.replace(/^v/i, "");
  const hasApk = !!pickApkAsset(release.assets || [], "");

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
    // App appends ?abi=… so the Worker can pick the matching split APK.
    download_url: hasApk ? `${origin}/download` : null,
  };
}

/**
 * Prefer ABI-specific APK when [preferredAbi] is set (e.g. "arm64-v8a"),
 * then universal, then any non-debug release APK.
 */
function pickApkAsset(assets, preferredAbi = "") {
  const list = Array.isArray(assets) ? assets : [];
  const abi = String(preferredAbi || "").toLowerCase();
  const abiAliases = abiAliasesFor(abi);

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
      if (n.includes("debug")) score -= 50;
      if (n.includes("release")) score += 3;
      if (n.includes("fdroid")) score += 2;

      // Exact / alias ABI match beats universal.
      if (abiAliases.some((alias) => n.includes(alias))) {
        score += 20;
      } else if (n.includes("universal") || n.includes("all") || n.includes("fat")) {
        score += 10;
      }

      // Mild preference for arm64 names when no ABI requested.
      if (!abi && (n.includes("arm64") || n.includes("aarch64"))) score += 1;
      return { a, score };
    })
    .sort((x, y) => y.score - x.score);
  return scored[0]?.a || null;
}

function abiAliasesFor(abi) {
  if (!abi) return [];
  const out = new Set([abi]);
  // Normalize common Android ABI spellings used in Gradle/split APK names.
  if (abi === "arm64-v8a" || abi === "arm64" || abi === "aarch64") {
    out.add("arm64-v8a");
    out.add("arm64");
    out.add("aarch64");
  } else if (abi === "armeabi-v7a" || abi === "armeabi" || abi === "armv7") {
    out.add("armeabi-v7a");
    out.add("armeabi");
    out.add("armv7");
  } else if (abi === "x86_64" || abi === "x86-64" || abi === "amd64") {
    out.add("x86_64");
    out.add("x86-64");
  } else if (abi === "x86") {
    out.add("x86");
  }
  return [...out];
}

// ── CI channel ──────────────────────────────────────────────────────────────
//
// The app's CI channel installs the newest successful `main` build directly from
// its Actions artifact, so nightly builds never pollute the Releases list.
//
// Artifact downloads require a token, which must never ship in the APK — hence
// this proxy. Note what it does NOT do: it never streams the ZIP. GitHub answers
// `/artifacts/{id}/zip` with a 302 to a short-lived signed URL that needs no
// auth, so the Worker just hands that URL to the client. The bytes travel
// straight from GitHub's storage, costing this Worker zero bandwidth and zero
// CPU (a free-tier Worker could not decompress a 50 MB archive anyway).

const CI_ARTIFACT_NAME = "agora-release";
const CI_WORKFLOW_BRANCH = "main";

/** Newest successful workflow run on [CI_WORKFLOW_BRANCH], or null. */
async function fetchLatestCiRun(env) {
  const apiUrl =
    `https://api.github.com/repos/${env.GITHUB_OWNER}/${env.GITHUB_REPO}` +
    `/actions/runs?branch=${CI_WORKFLOW_BRANCH}&status=success&per_page=1`;
  const res = await fetch(apiUrl, { headers: githubHeaders(env, "application/vnd.github+json") });
  if (!res.ok) return null;
  const data = await res.json();
  return (data.workflow_runs && data.workflow_runs[0]) || null;
}

/** The APK artifact of [run], or null when it has expired (artifacts live 90 days). */
async function fetchRunArtifact(env, run) {
  const res = await fetch(run.artifacts_url, {
    headers: githubHeaders(env, "application/vnd.github+json"),
  });
  if (!res.ok) return null;
  const data = await res.json();
  const artifacts = (data.artifacts || []).filter((a) => !a.expired);
  return (
    artifacts.find((a) => a.name === CI_ARTIFACT_NAME) ||
    artifacts.find((a) => /\.apk|apk/i.test(a.name)) ||
    artifacts[0] ||
    null
  );
}

async function handleCiLatest(env, origin) {
  const run = await fetchLatestCiRun(env);
  if (!run) {
    return new Response(null, { status: 204, headers: { ...CORS, "Cache-Control": "public, max-age=60" } });
  }
  const artifact = await fetchRunArtifact(env, run);
  if (!artifact) {
    return json({ error: "no_artifact", message: "Build has no unexpired artifact" }, 404);
  }
  return json(
    {
      // Not a semver — the app compares run numbers for the CI channel.
      run_number: run.run_number,
      run_id: run.id,
      sha: (run.head_sha || "").slice(0, 7),
      // "ci-<run>-<sha>" is what the app shows as the available version.
      version: `ci-${run.run_number}-${(run.head_sha || "").slice(0, 7)}`,
      title: run.display_title || run.head_commit?.message?.split("\n")[0] || "",
      body: run.head_commit?.message || "",
      html_url: run.html_url,
      published_at: run.updated_at || run.created_at,
      size_bytes: artifact.size_in_bytes,
      // The archive is a ZIP of every split APK; the client picks its ABI.
      download_url: `${origin}/ci/download`,
      is_zip: true,
    },
    200,
    { "Cache-Control": "public, max-age=120" },
  );
}

async function handleCiDownload(env) {
  const run = await fetchLatestCiRun(env);
  if (!run) return json({ error: "no_run" }, 404);
  const artifact = await fetchRunArtifact(env, run);
  if (!artifact) return json({ error: "no_artifact" }, 404);

  // manual redirect: we want the Location header, not the body.
  const res = await fetch(artifact.archive_download_url, {
    headers: githubHeaders(env, "application/vnd.github+json"),
    redirect: "manual",
  });
  const signedUrl = res.headers.get("Location");
  if (!signedUrl) {
    return json({ error: "no_signed_url", status: res.status }, 502);
  }
  // 302 straight to storage — the token never leaves the Worker and the bytes
  // never pass through it.
  return new Response(null, {
    status: 302,
    headers: { ...CORS, Location: signedUrl, "Cache-Control": "no-store" },
  });
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
