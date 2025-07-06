#!/bin/bash

grep drbd /etc/modules && \
  echo "drbd module is configured to load during startup. Please remove it first from /etc/modules." && \
  exit 1

lsmod | grep drbd
drbd_loaded=$?


echo "Removing the current installed drbd kernel module"
modprobe -r drbd

set -e

# reboot the machine if the kernel was loaded
if [ $drbd_loaded = 0 ] ; then echo "drbd module was loaded. Please re-run the script after reboot." && exit 1 ; fi

echo "Installing linbit-keyring ..."
wget -O /tmp/linbit-keyring.deb https://packages.linbit.com/public/linbit-keyring.deb
dpkg -i /tmp/linbit-keyring.deb

echo "Installing drbd repo ..."
# other repo than the proxmox one?
PVERS=8 && echo "deb [signed-by=/etc/apt/trusted.gpg.d/linbit-keyring.gpg] \
http://packages.linbit.com/public/ proxmox-$PVERS drbd-9" > /etc/apt/sources.list.d/linbit.list
apt update -y

echo "Installing drbd packages ..."
apt install -y drbd-dkms drbd-utils

echo "Load dbrd module"
modprobe drbd
echo drbd >> /etc/modules

echo "Install successful. It's recommended to reboot the machine."
