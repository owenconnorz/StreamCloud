# Banner advertising setup

## Current state

The Movies and Adult browsing screens have compact, centred 300 × 100 banner
placements. They do not cover playback controls and are not placed on TV screens
or Android Auto. The Adult placement is behind the existing age/PIN checks.

**Live advertising is disabled. This build cannot earn advertising revenue.**
The supplied ExoClick tag is retained, but it is never requested without the
remaining setup. Missing configuration takes up no screen space.

To inspect the placement without making an ad request, use
**Settings → Privacy → Advertising → Offline 300 × 100 banner preview**.
This switch is not consent. It defaults off and can be turned off at any time.
Debug builds must never request paid ads.

## Owner setup still required

1. In ExoClick, confirm that approved banner zone **6033416** uses the **300 × 100**
   mobile banner format. An app-side size cannot change an account-side zone.
   If a replacement zone is necessary, obtain its public ad tag.
2. Confirm the zone is approved for both app placements. Configure appropriate
   creative/category restrictions, particularly non-explicit inventory on the
   Movies page; use separate approved zones if required.
3. Set up an app consent management platform. **consentmanager** is the suggested
   option because it documents native Android support and participates in IAB TCF.
   Verify its current plan and content eligibility directly before subscribing.
   Configure the actual advertising vendors, purposes, disclosures and privacy
   policy, including ExoClick (IAB vendor 997). Account approval does not
   establish end-user consent.
4. Provide the CMP's **public app integration configuration**, such as its Code-ID
   and domain. Do not send passwords, API secrets or payout details.

## Engineering activation checklist

- Integrate the selected CMP using documented, Android-toolchain-compatible APIs.
  Do not simply change the readiness constants in `ads/AdPolicy.kt`.
- Obtain genuine current consent, retain the required receipt, and transport the
  CMP's real consent data to the ad WebView. Never synthesize TCF strings or treat
  the preview preference as advertising consent.
- Provide an accessible privacy control to reopen choices and withdraw consent.
  Destroy the ad WebView immediately on withdrawal and fail closed on CMP errors
  or expired consent. Do not erase other providers' global WebView cookies.
- Verify confirmed zone dimensions, placement approval and creative restrictions.
- Verify no requests before consent, on rejection, in debug, on TV/Auto, or after
  disposal. Keep browsing terms, media IDs, provider pages and history out of ad
  parameters. Audit request/click and lifecycle behavior on a device using the
  network's approved testing method, never by clicking paid ads.
- Re-run Android Debug/Release builds and policy tests. Publishing a new release
  remains a separate owner decision.

## References

- [ExoClick Android app integration](https://docs.exoclick.com/tutorials/tutorials/publishers-tutorials/adding-exoclick-ad-zones-to-apps)
- [ExoClick mobile banner dimensions](https://docs.exoclick.com/exoclick-docs/ad-formats)
- [ExoClick EU/UK consent policy](https://www.exoclick.com/eu-user-consent-policy)
- [consentmanager Android integration](https://help.consentmanager.net/books/cmp/page/android-1-consentmanager-sdk-integration)