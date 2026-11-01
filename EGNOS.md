# EGNOS

EGNOS is the European SBAS (Satellite Based Augmentation System). Geostationary satellites (GEOs)
broadcast corrections and integrity data for GPS (and GLONASS) on the GPS L1 frequency. GPSTest
shows EGNOS in two ways.

## On the device

Without any setup, the Status screen shows an **EGNOS** card whenever the phone tracks an EGNOS
GEO:

- each GEO by PRN, name and orbital position, with its C/N0 and whether the phone uses it in the
  fix,
- the same name and position in the satellite details of the SBAS group (tap the satellite).

| PRN | Satellite    | Position | Note    |
|-----|--------------|----------|---------|
| 121 | Eutelsat 5WB | 5° W     |         |
| 123 | Astra 5B     | 31.5° E  |         |
| 126 | Inmarsat 4F2 | 63.9° E  |         |
| 136 | SES-5        | 5° E     |         |
| 120 | Inmarsat 3F2 | 15.5° W  | retired |
| 124 | Artemis      | 21.5° E  | retired |

Which GEOs are operational changes over time (PRN 121 became operational and PRN 123 moved to test
in 2025), so GPSTest doesn't hard-code it. A GEO in test broadcasts Message Type 0 ("don't use"),
which the card shows when EDAS is set up.

## EGNOS messages from EDAS

Android doesn't give apps the SBAS messages: `GnssNavigationMessage` has no SBAS type. GPSTest can
instead receive the same messages over the internet from the **EGNOS Data Access Service (EDAS)**,
using ESA's SISNeT protocol.

EDAS is free of charge, but you need your own account:

1. Register at the [EGNOS User Support website](https://egnos-user-support.essp-sas.eu/), then
   fill in the EDAS registration form (Services → EDAS Service → Registration).
2. From the EDAS user information you receive, take the SISNeT server, port, user name and
   password.
3. In GPSTest, open **Settings → EGNOS**, turn on *Receive EGNOS messages from EDAS* and enter
   them.

While tracking, GPSTest then polls the server once per second. The card shows:

- **Connection**: server and state (live, login failed, no connection); *Stale* if no message
  arrived for 30 s,
- **Messages**: count, age of the last one, CRC errors, and the message types received,
- **PRN mask**: the satellites EGNOS corrects,
- **Corrected**: satellites with a current fast correction and a usable UDREI; satellites *not
  monitored* (UDREI 14) and *don't use* (UDREI 15) are listed separately,
- **Long-term**: satellites with current orbit and clock corrections,
- **Ionosphere**: monitored grid points out of those in the IGP mask,
- **GEO almanac**: the SBAS GEOs in Message Type 17.

In the satellite list, tap a GPS or GLONASS satellite to see its EGNOS fast correction, UDRE and
age.

Notes:

- GPSTest only shows the messages; it doesn't change the phone's position. Whether the phone uses
  EGNOS is up to its GNSS chipset.
- Timeouts are simplified (fast corrections 18 s, long-term 360 s, ionosphere 600 s), roughly the
  en route/NPA values; DO-229 derives the exact fast correction timeouts from the degradation
  parameters.
- SISNeT is plain TCP, so the user name and password are sent unencrypted. Don't reuse a password.
- EDAS may only be used for purposes that aren't safety critical.

### Decoded message types

| MT     | Content                                    |
|--------|--------------------------------------------|
| 0      | Don't use                                  |
| 1      | PRN mask                                   |
| 2-5    | Fast corrections and UDREIs                |
| 6      | Integrity (UDREIs)                         |
| 7      | Fast correction degradation factors        |
| 9      | GEO navigation                             |
| 17     | GEO almanacs                               |
| 18     | Ionospheric grid point mask                |
| 24     | Mixed fast and long-term corrections       |
| 25     | Long-term corrections                      |
| 26     | Ionospheric delays                         |

Other types are counted but not decoded. Messages are checked with the SBAS CRC-24Q, independently
of the SISNeT line checksum.

The decoder can also read files of ESA's EGNOS Message Server (EMS), which is used by the unit
tests.
