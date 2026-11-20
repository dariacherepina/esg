# VM

Measurement machine for Measurement Study 1 and Experiment 2 (self-hosted GitHub Actions runner).

## SSH key

Created on 2026-11-20 on the author's laptop with `ssh-keygen -t ed25519 -C "thesis-vm"`.
Access to the VM only via this SSH key (no password login); the private key stays on the laptop.

## Azure attempt (2026-11-20, not used)

Planned: Ubuntu Server 24.04 LTS, 4 dedicated vCPUs, 16 GiB RAM, 64 GiB Standard SSD, SSH (22) only, auto-shutdown at 02:00 (Berlin).

| Region / size | Result |
|---|---|
| Germany West Central, D4s_v7 | rejected: quota for the Dsv7 family is 0; no size deployable in this region |
| East US, D4s_v4 | rejected: region not allowed by the subscription policy "Allowed resource deployment regions" |
| Sweden Central, Poland Central, Italy North, Norway East | only burstable B-series sizes deployable |

The Azure for Students subscription only allows the regions polandcentral, germanywestcentral, norwayeast, italynorth and swedencentral. In none of them was a non-burstable 4-vCPU size available without a quota increase. Burstable B-series VMs were rejected because CPU credit depletion during multi-hour measurements would throttle the CPU and systematically distort the comparison between variants.

No VM was created; the empty resource group `thesis-rg` was deleted on 2026-11-20. Costs: 0.

Open options: university VM (preferred), Azure quota increase for a D family in an allowed region, or the author's laptop.

## Cost protection

- Budget `thesis-limit`: 10 USD per month on the billing account, alerts at 30 %, 60 %, 90 % actual and 100 % forecasted
- Azure for Students without a payment method: no charges beyond the credit
- Everything in one resource group, deleted after the measurements
- Remaining credit after the attempt: €89.77
