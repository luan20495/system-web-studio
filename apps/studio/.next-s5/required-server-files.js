self.__SERVER_FILES_MANIFEST={
  "version": 1,
  "config": {
    "env": {},
    "webpack": null,
    "typescript": {
      "ignoreBuildErrors": false
    },
    "typedRoutes": false,
    "distDir": ".next-s5",
    "cleanDistDir": true,
    "assetPrefix": "",
    "cacheMaxMemorySize": 52428800,
    "configOrigin": "next.config.ts",
    "useFileSystemPublicRoutes": true,
    "generateEtags": true,
    "pageExtensions": [
      "tsx",
      "ts",
      "jsx",
      "js"
    ],
    "instrumentationClientInject": [],
    "poweredByHeader": false,
    "compress": true,
    "images": {
      "deviceSizes": [
        640,
        750,
        828,
        1080,
        1200,
        1920,
        2048,
        3840
      ],
      "imageSizes": [
        32,
        48,
        64,
        96,
        128,
        256,
        384
      ],
      "path": "/_next/image",
      "loader": "default",
      "loaderFile": "",
      "domains": [],
      "disableStaticImages": false,
      "minimumCacheTTL": 14400,
      "formats": [
        "image/webp"
      ],
      "maximumRedirects": 3,
      "maximumResponseBody": 50000000,
      "dangerouslyAllowLocalIP": false,
      "dangerouslyAllowSVG": false,
      "contentSecurityPolicy": "script-src 'none'; frame-src 'none'; sandbox;",
      "contentDispositionType": "attachment",
      "localPatterns": [
        {
          "pathname": "**",
          "search": ""
        }
      ],
      "remotePatterns": [],
      "qualities": [
        75
      ],
      "unoptimized": true,
      "customCacheHandler": false
    },
    "devIndicators": {
      "position": "bottom-left"
    },
    "onDemandEntries": {
      "maxInactiveAge": 60000,
      "pagesBufferLength": 5
    },
    "basePath": "",
    "sassOptions": {},
    "trailingSlash": false,
    "i18n": null,
    "productionBrowserSourceMaps": false,
    "excludeDefaultMomentLocales": true,
    "reactProductionProfiling": false,
    "reactStrictMode": true,
    "reactMaxHeadersLength": 6000,
    "httpAgentOptions": {
      "keepAlive": true
    },
    "logging": {
      "serverFunctions": true,
      "browserToTerminal": "warn"
    },
    "compiler": {},
    "expireTime": 31536000,
    "staticPageGenerationTimeout": 60,
    "modularizeImports": {
      "@mui/icons-material": {
        "transform": "@mui/icons-material/{{member}}"
      },
      "lodash": {
        "transform": "lodash/{{member}}"
      }
    },
    "outputFileTracingRoot": "/Users/hoangluan/code/xweb-c5",
    "enablePrerenderSourceMaps": true,
    "cacheComponents": false,
    "cacheLife": {
      "default": {
        "stale": 300,
        "revalidate": 900,
        "expire": 4294967294
      },
      "seconds": {
        "stale": 30,
        "revalidate": 1,
        "expire": 60
      },
      "minutes": {
        "stale": 300,
        "revalidate": 60,
        "expire": 3600
      },
      "hours": {
        "stale": 300,
        "revalidate": 3600,
        "expire": 86400
      },
      "days": {
        "stale": 300,
        "revalidate": 86400,
        "expire": 604800
      },
      "weeks": {
        "stale": 300,
        "revalidate": 604800,
        "expire": 2592000
      },
      "max": {
        "stale": 300,
        "revalidate": 2592000,
        "expire": 31536000
      }
    },
    "cacheHandlers": {},
    "experimental": {
      "appNewScrollHandler": true,
      "coldCacheBadge": false,
      "devValidationWorker": true,
      "useSkewCookie": false,
      "cssChunking": true,
      "multiZoneDraftMode": false,
      "appNavFailHandling": false,
      "prerenderEarlyExit": true,
      "serverMinification": true,
      "linkNoTouchStart": false,
      "caseSensitiveRoutes": false,
      "cachedNavigations": false,
      "dynamicOnHover": false,
      "useOffline": false,
      "varyParams": true,
      "optimisticRouting": true,
      "instrumentationClientRouterTransitionEvents": false,
      "prefetchInlining": {
        "maxSize": 2048,
        "maxBundleSize": 10240
      },
      "preloadEntriesOnStart": true,
      "clientRouterFilter": true,
      "clientRouterFilterRedirects": false,
      "fetchCacheKeyPrefix": "",
      "proxyPrefetch": "flexible",
      "optimisticClientCache": true,
      "manualClientBasePath": false,
      "cpus": 9,
      "memoryBasedWorkersCount": false,
      "imgOptConcurrency": null,
      "imgOptOperationCache": null,
      "imgOptTimeoutInSeconds": 7,
      "imgOptMaxInputPixels": 268402689,
      "imgOptSequentialRead": null,
      "isrFlushToDisk": true,
      "workerThreads": false,
      "optimizeCss": false,
      "nextScriptWorkers": false,
      "scrollRestoration": false,
      "externalDir": false,
      "devMemoryThresholdRestart": true,
      "disableOptimizedLoading": false,
      "gzipSize": true,
      "craCompat": false,
      "esmExternals": true,
      "fullySpecified": false,
      "swcTraceProfiling": false,
      "forceSwcTransforms": false,
      "requestInsights": false,
      "largePageDataBytes": 128000,
      "typedEnv": false,
      "parallelServerCompiles": false,
      "parallelServerBuildTraces": false,
      "ppr": false,
      "authInterrupts": false,
      "webpackMemoryOptimizations": false,
      "optimizeServerReact": true,
      "strictRouteTypes": false,
      "useTypeScriptCli": true,
      "removeUncaughtErrorAndRejectionListeners": false,
      "validateRSCRequestHeaders": true,
      "staleTimes": {
        "dynamic": 0,
        "static": 300
      },
      "reactDebugChannel": true,
      "serverComponentsHmrCache": true,
      "serverComponentsHmrCancellation": false,
      "staticGenerationMaxConcurrency": 8,
      "staticGenerationMinPagesPerWorker": 25,
      "transitionIndicator": false,
      "gestureTransition": false,
      "inlineCss": false,
      "useCache": false,
      "globalNotFound": false,
      "browserDebugInfoInTerminal": "warn",
      "lockDistDir": true,
      "proxyClientMaxBodySize": 10485760,
      "hideLogsAfterAbort": false,
      "mcpServer": true,
      "turbopackFileSystemCacheForDev": true,
      "turbopackFileSystemCacheForBuild": true,
      "turbopackInferModuleSideEffects": true,
      "turbopackPluginRuntimeStrategy": "childProcesses",
      "turbopackMemoryEvictionMode": "auto",
      "optimizePackageImports": [
        "lucide-react",
        "date-fns",
        "lodash-es",
        "ramda",
        "antd",
        "react-bootstrap",
        "ahooks",
        "@ant-design/icons",
        "@headlessui/react",
        "@headlessui-float/react",
        "@heroicons/react/20/solid",
        "@heroicons/react/24/solid",
        "@heroicons/react/24/outline",
        "@visx/visx",
        "@tremor/react",
        "rxjs",
        "@mui/material",
        "@mui/icons-material",
        "recharts",
        "react-use",
        "effect",
        "@effect/schema",
        "@effect/platform",
        "@effect/platform-node",
        "@effect/platform-browser",
        "@effect/platform-bun",
        "@effect/sql",
        "@effect/sql-mssql",
        "@effect/sql-mysql2",
        "@effect/sql-pg",
        "@effect/sql-sqlite-node",
        "@effect/sql-sqlite-bun",
        "@effect/sql-sqlite-wasm",
        "@effect/sql-sqlite-react-native",
        "@effect/rpc",
        "@effect/rpc-http",
        "@effect/typeclass",
        "@effect/experimental",
        "@effect/opentelemetry",
        "@material-ui/core",
        "@material-ui/icons",
        "@tabler/icons-react",
        "mui-core",
        "react-icons/ai",
        "react-icons/bi",
        "react-icons/bs",
        "react-icons/cg",
        "react-icons/ci",
        "react-icons/di",
        "react-icons/fa",
        "react-icons/fa6",
        "react-icons/fc",
        "react-icons/fi",
        "react-icons/gi",
        "react-icons/go",
        "react-icons/gr",
        "react-icons/hi",
        "react-icons/hi2",
        "react-icons/im",
        "react-icons/io",
        "react-icons/io5",
        "react-icons/lia",
        "react-icons/lib",
        "react-icons/lu",
        "react-icons/md",
        "react-icons/pi",
        "react-icons/ri",
        "react-icons/rx",
        "react-icons/si",
        "react-icons/sl",
        "react-icons/tb",
        "react-icons/tfi",
        "react-icons/ti",
        "react-icons/vsc",
        "react-icons/wi"
      ],
      "useCacheTimeout": 54,
      "instantInsights": {
        "validationLevel": "warning"
      },
      "trustHostHeader": false,
      "isExperimentalCompile": false
    },
    "htmlLimitedBots": "[\\w-]+-Google|Google-[\\w-]+|Chrome-Lighthouse|Slurp|DuckDuckBot|baiduspider|yandex|sogou|bitlybot|tumblr|vkShare|quora link preview|redditbot|ia_archiver|Bingbot|BingPreview|applebot|facebookexternalhit|facebookcatalog|Twitterbot|LinkedInBot|Slackbot|Discordbot|WhatsApp|SkypeUriPreview|Yeti|googleweblight",
    "bundlePagesRouterDependencies": false,
    "configFileName": "next.config.ts",
    "agentRules": false,
    "turbopack": {
      "root": "/Users/hoangluan/code/xweb-c5"
    },
    "transpilePackages": [
      "@xweb/api-client",
      "@xweb/auth",
      "@xweb/i18n",
      "@xweb/permissions",
      "@xweb/types",
      "@xweb/ui"
    ],
    "repoRoot": "/Users/hoangluan/code/xweb-c5",
    "distDirRoot": ".next-s5",
    "_originalRewrites": {
      "beforeFiles": [
        {
          "source": "/api/:path*",
          "destination": "http://127.0.0.1:47300/api/:path*"
        },
        {
          "source": "/oauth2/:path*",
          "destination": "http://127.0.0.1:47300/oauth2/:path*"
        },
        {
          "source": "/login/oauth2/:path*",
          "destination": "http://127.0.0.1:47300/login/oauth2/:path*"
        }
      ],
      "afterFiles": [],
      "fallback": []
    }
  },
  "appDir": "/Users/hoangluan/code/xweb-c5/apps/studio",
  "relativeAppDir": "apps/studio",
  "files": [
    ".next-s5/package.json",
    ".next-s5/routes-manifest.json",
    ".next-s5/server/pages-manifest.json",
    ".next-s5/build-manifest.json",
    ".next-s5/prerender-manifest.json",
    ".next-s5/server/functions-config-manifest.json",
    ".next-s5/server/middleware-manifest.json",
    ".next-s5/server/middleware-build-manifest.js",
    ".next-s5/server/app-paths-manifest.json",
    ".next-s5/app-path-routes-manifest.json",
    ".next-s5/server/server-reference-manifest.js",
    ".next-s5/server/server-reference-manifest.json",
    ".next-s5/server/prefetch-hints.json",
    ".next-s5/BUILD_ID",
    ".next-s5/server/next-font-manifest.js",
    ".next-s5/server/next-font-manifest.json",
    ".next-s5/required-server-files.json"
  ],
  "ignore": []
}