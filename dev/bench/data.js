window.BENCHMARK_DATA = {
  "lastUpdate": 1790374162022,
  "repoUrl": "https://github.com/leugenea/qmix",
  "entries": {
    "Lizard erosion": [
      {
        "commit": {
          "author": {
            "email": "leugenea@gmail.com",
            "name": "Luckyanets Eugene",
            "username": "leugenea"
          },
          "committer": {
            "email": "noreply@github.com",
            "name": "GitHub",
            "username": "web-flow"
          },
          "distinct": true,
          "id": "c854b62f03fa152fe46145747268cad2f7f99ed1",
          "message": "ci: record erosion history on main and publish the chart (#227)\n\nRefs #198.",
          "timestamp": "2026-09-25T20:08:54+03:00",
          "tree_id": "5806382fa1026fe056e31878bb7cd53947799b08",
          "url": "https://github.com/leugenea/qmix/commit/c854b62f03fa152fe46145747268cad2f7f99ed1"
        },
        "date": 1790356169510,
        "tool": "customSmallerIsBetter",
        "benches": [
          {
            "name": "Overall erosion",
            "value": 34.18758948687785,
            "unit": "%"
          },
          {
            "name": "Go erosion",
            "value": 35.66333549473505,
            "unit": "%"
          },
          {
            "name": "Kotlin erosion",
            "value": 32.96752101886259,
            "unit": "%"
          },
          {
            "name": "JS erosion",
            "value": 0,
            "unit": "%"
          },
          {
            "name": "Functions with CCN > 10",
            "value": 23,
            "unit": "functions"
          }
        ]
      }
    ],
    "Code erosion": [
      {
        "commit": {
          "author": {
            "email": "leugenea@gmail.com",
            "name": "Luckyanets Eugene",
            "username": "leugenea"
          },
          "committer": {
            "email": "noreply@github.com",
            "name": "GitHub",
            "username": "web-flow"
          },
          "distinct": true,
          "id": "0e63730b11b68e142102c8443316fb87ae0f2942",
          "message": "ci: measure Kotlin complexity with a tree-sitter counter and report erosion per language (#230)",
          "timestamp": "2026-09-26T01:08:42+03:00",
          "tree_id": "3d3fd400a1fa68da90b7c8c4faa267af3ba77085",
          "url": "https://github.com/leugenea/qmix/commit/0e63730b11b68e142102c8443316fb87ae0f2942"
        },
        "date": 1790374160397,
        "tool": "customSmallerIsBetter",
        "benches": [
          {
            "name": "Go erosion",
            "value": 35.66333549473505,
            "unit": "%"
          },
          {
            "name": "Go CCN > 10",
            "value": 14,
            "unit": "functions"
          },
          {
            "name": "Kotlin erosion",
            "value": 58.44101471485502,
            "unit": "%"
          },
          {
            "name": "Kotlin CCN > 10",
            "value": 19,
            "unit": "functions"
          },
          {
            "name": "JS erosion",
            "value": 0,
            "unit": "%"
          },
          {
            "name": "JS CCN > 10",
            "value": 0,
            "unit": "functions"
          }
        ]
      }
    ]
  }
}