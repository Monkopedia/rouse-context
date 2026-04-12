# Notes for Issue #57

## Could not strictly reproduce the on-device bug in a unit test

The fix in `AppNavigation.kt` replaces a `bottomBar` slot that was always
populated (with an `AnimatedVisibility`-wrapped `NavigationBar` that was
invisible on detail screens) with a conditional that only populates the
slot when `showBottomBar == true`.

Suspected mechanism: Material3 `Scaffold` measures the `bottomBar` slot,
and when that slot emits a zero-height composable it still counts as
"present", so `contentWindowInsets.bottom` gets dropped from the content
`PaddingValues`. That would cause the `Disable Integration` / `Cancel
Setup` button to sit flush with the physical bottom edge, behind the
gesture-navigation area.

I attempted to reproduce this via a Robolectric unit test at
`app/src/test/java/com/rousecontext/app/ui/screenshots/BottomInsetScreenshotTest.kt`,
replicating the exact original wiring (`Column(animateContentSize)` +
`AnimatedVisibility(visible=false)` wrapping a NavigationBar) inside a
Scaffold whose `contentWindowInsets = WindowInsets(bottom = 48.dp)`. In
that test environment, Material3 1.3.2 correctly applied the 48dp bottom
padding regardless of whether the bottomBar slot was empty or populated
with a hidden NavigationBar. So the pre-fix reproduction path did not
produce the observed clipping in unit tests.

Explanations considered:

* The bug only manifests in real `enableEdgeToEdge` flow with a live
  `WindowInsetsCompat` chain — something that Robolectric does not fully
  simulate.
* The Material3 version in the repo (`compose-bom 2026.03.00`, material3
  1.3.2) may behave slightly differently from older versions where the
  condition was `bottomBarPlaceables.isEmpty()`-based.
* The bug may actually be a `AnimatedVisibility` + `animateContentSize`
  interaction during tab-to-detail navigation that leaves a stale
  measured height briefly.

## Why the fix is still the right move

* It simplifies the bottomBar slot: `AnimatedVisibility` +
  `animateContentSize` were only useful for the brief slide animation
  of the navigation bar itself. With the conditional, the bar is
  mounted/unmounted cleanly from the tree.
* It removes any possibility of a populated-but-zero-height bottomBar,
  which is exactly the shape that causes clipping in every Material3
  revision where the `bottomBarPlaceables.isEmpty()` branch is taken.
* Visual verification via `99_bottom_inset_manage_dark.png` renders the
  production structure with a simulated 48dp gesture-bar region and
  confirms the `Disable Integration` button renders entirely above it.

## Outstanding: verify on-device

The reporter's Pixel was not available in this environment. Before
merging, smoke-test on a Pixel running gesture navigation:

1. Open any integration manage screen. The `Disable Integration` /
   `Cancel Setup` button must be fully tappable and not tucked behind
   the gesture handle.
2. Open each setup screen (Health Connect, Notifications, Outreach,
   Usage). The primary action button at the bottom must be fully
   visible above the gesture area.
3. Tab between Home / Audit / Settings. The persistent `NavigationBar`
   must still enter cleanly from the bottom when you return to a tab
   route (the conditional replaces AnimatedVisibility with simple mount/
   unmount, so re-check that the transition still looks acceptable).
