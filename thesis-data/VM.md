# VM

Measurement machine for Measurement Study 1 and Experiment 2 (self-hosted GitHub Actions runner).

## SSH key

Created on 2026-11-20 on the author's laptop with `ssh-keygen -t ed25519 -C "thesis-vm"`.
Access to the VM only via this SSH key (no password login); the private key stays on the laptop.

## Azure

| Setting | Value |
|---|---|
| Subscription | Azure for Students |
| Resource group | `thesis-rg` |
| VM name | `thesis-vm` |
| Region | |
| Size | Standard_D4s_v5 (4 vCPUs, 16 GiB) |
| Image | Ubuntu Server 24.04 LTS – x64 Gen2 |
| OS disk | 64 GiB Standard SSD |
| Inbound ports | SSH (22) only |
| Auto-shutdown | |
| Created | |
| Deleted | |

## Cost protection

- Budget `thesis-limit`: 10 USD per month on the billing account, alerts at 30 %, 60 %, 90 % actual and 100 % forecasted
- Azure for Students without a payment method: no charges beyond the credit
- Everything in one resource group, deleted after the measurements
