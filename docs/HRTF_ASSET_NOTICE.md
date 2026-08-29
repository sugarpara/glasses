# HRTF Asset Notice

This project contains a derived 64 x 64 HRTF grid used by the Android audio engine:

- `app/src/main/assets/hrtf_grid64.bin`
- `app/src/main/assets/hrtf_grid64_meta.json`

## Source

The metadata identifies the source SOFA file as:

`P0020_FreeFieldComp_48kHz.sofa`

Official source records checked on 2026-08-30:

- Dataset: SONICOM Acoustic HRTFs 48k, SONICOM Ecosystem database #3
- Subject/data set: P0020
- DOI: `10.60887/2x5fmfnjx320yt7a`
- Database: https://ecosystem.sonicom.eu/databases/3
- Data file record: https://ecosystem.sonicom.eu/datafiles/2026

Suggested source citation from the official database page:

Engel, I., Daugintis, R., Vicente, T., Hogg, A., Pauwels, J., Tournier, A.,
Meyer, J., Martin, V., Marggraf-Turley, N., Webb, J., Pirard, L., La Magna,
N., Turvey, O., Poole, K., and Picinali, L. (2026). "SONICOM Acoustic HRTFs
48k", The SONICOM Ecosystem: Database #3.

## Derived Grid

The bundled binary is not the original SOFA file. According to the bundled metadata it was
interpolated to 64 rows x 64 columns, two receivers, 256 samples per HRIR, at 48 kHz. The binary
layout is `[row][column][receiver][sample]` with little-endian Float32 values.

Asset integrity values:

| Asset | Size | SHA-256 |
|---|---:|---|
| `hrtf_grid64.bin` | 8,388,608 bytes | `0BCC260A476D0FCACD312D7FA9791B3DE49452B4E97C2F7AE37BB89C1320D924` |
| `hrtf_grid64_meta.json` | 1,536 bytes | `9F9E499D71A226B694BA3204BE93E749B67C24D73D33760455C7409B1BADE02C` |

## License Review

The SONICOM Ecosystem Terms of Use state that the default license for open content is Creative
Commons Attribution 4.0 International (CC BY 4.0):

https://creativecommons.org/licenses/by/4.0/

The Terms of Use also state that repository content is made available for non-military purposes
only and that users must respect any content-specific license conditions:

https://ecosystem.sonicom.eu/terms-of-use

No asset-specific license override or bundled license file was found in the reference Android
project. Before public distribution, the project owner must confirm that redistributing this
derived binary inside an APK is permitted, preserve the required attribution, and review the
additional SONICOM Terms of Use. This is a release-blocking license review item, not a runtime
blocker for local development and testing.
