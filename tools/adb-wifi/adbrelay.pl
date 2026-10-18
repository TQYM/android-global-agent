#!/usr/bin/perl
# adbrelay.pl — 本机 TCP 中继，把 127.0.0.1:LISTEN_PORT 转发到 TARGET_HOST:TARGET_PORT
#
# 为什么要它：
#   本机存在活动的 Network Extension（蓝猫VPN，bundleID com.lanmaovpn.mac.vpn.tunnel），
#   它会对“第三方/非系统”进程直连局域网设备的连接返回 EHOSTUNREACH（No route to host）。
#   实测：adb / node / CLT python 被拦；系统自带 nc / curl / bash(/dev/tcp) / perl 不受影响。
#   因此这里用 perl（系统自带、实测可达）做转发，让 adb 连 127.0.0.1 再落到手机的 WiFi 地址。
#
# 用法:
#   perl adbrelay.pl [LISTEN_PORT] [TARGET_HOST] [TARGET_PORT]
#   perl adbrelay.pl 5599 192.168.1.106 5555
use strict;
use warnings;
use IO::Socket::INET;
use IO::Select;
$| = 1;
$SIG{PIPE} = 'IGNORE';

my $LISTEN_PORT = $ARGV[0] // 5599;
my $TARGET_HOST = $ARGV[1] // '192.168.1.106';
my $TARGET_PORT = $ARGV[2] // 5555;

my $listen = IO::Socket::INET->new(
    LocalAddr => '127.0.0.1', LocalPort => $LISTEN_PORT,
    Proto     => 'tcp', Listen => 16, Reuse => 1,
) or die "listen 127.0.0.1:$LISTEN_PORT failed: $!\n";
$listen->autoflush(1);
print "relay listening 127.0.0.1:$LISTEN_PORT -> $TARGET_HOST:$TARGET_PORT\n";

while (my $client = $listen->accept) {
    my $remote = IO::Socket::INET->new(
        PeerAddr => $TARGET_HOST, PeerPort => $TARGET_PORT, Proto => 'tcp', Timeout => 5,
    );
    if (!$remote) {
        print "target $TARGET_HOST:$TARGET_PORT connect failed: $!\n";
        close $client;
        next;
    }

    my $pid = fork();
    if (!defined $pid) { close $client; close $remote; next; }
    if ($pid) { close $client; close $remote; next; }    # 父进程继续 accept

    close $listen;
    $client->autoflush(1);
    $remote->autoflush(1);
    my $sel = IO::Select->new($client, $remote);
    while (my @ready = $sel->can_read()) {
        my $dead = 0;
        for my $s (@ready) {
            my $buf;
            my $n = sysread($s, $buf, 65536);
            if (!defined $n || $n == 0) { $dead = 1; last; }
            my $other = ($s == $client) ? $remote : $client;
            my $off = 0;
            while ($off < $n) {
                my $w = syswrite($other, $buf, $n - $off, $off);
                if (!defined $w) { $dead = 1; last; }
                $off += $w;
            }
            last if $dead;
        }
        last if $dead;
    }
    close $client;
    close $remote;
    exit 0;
}
