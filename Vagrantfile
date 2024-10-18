# -*- mode: ruby -*-
# vi: set ft=ruby :

def add_disk(config, disk_path)
  if(!File.exist?(disk_path))
    config.vm.provider "virtualbox" do |v|
      v.customize ['createhd', '--filename', disk_path, '--size', 20 * 1024]
    end
  end
  config.vm.provider "virtualbox" do |v|
    v.customize ['storageattach', :id, '--storagectl', 'SATA Controller', '--port', 1, '--device', 0, '--type', 'hdd', '--medium', disk_path]
  end
end


distro = "hashicorp/bionic64"


Vagrant.configure("2") do |config|
  config.vm.provision "shell" do |s|
    ssh_pub_key = File.readlines("#{Dir.home}/.ssh/id_rsa.pub").first.strip
    s.inline = <<-SHELL
      echo #{ssh_pub_key} >> /home/vagrant/.ssh/authorized_keys
    SHELL
  end

  # disable firewall
  config.vm.provision "shell", inline: "ufw disable"

  # requirement for longhorn
  config.vm.provision "shell", inline: "apt-get install open-iscsi"

  config.vm.provider "virtualbox" do |v|
    v.memory = 2048
  end

  config.vm.define "postgres-server-1" do |v|
    v.vm.box = distro
    v.vm.hostname = "postgres-server-1"
    v.vm.network "private_network", ip: "192.168.56.2"
    add_disk v, '/data/vagrant/disks/postgres-drbd-1.vdi'
  end

  config.vm.define "postgres-server-2" do |v|
    v.vm.box = distro
    v.vm.hostname = "postgres-server-2"
    v.vm.network "private_network", ip: "192.168.56.3"
    add_disk v, '/data/vagrant/disks/postgres-drbd-2.vdi'
  end

  config.vm.define "postgres-server-3" do |v|
    v.vm.box = distro
    v.vm.hostname = "postgres-server-3"
    v.vm.network "private_network", ip: "192.168.56.4"
  end


end
