import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';
import '../api.dart';
import '../models.dart';
import '../theme.dart';
import '../widgets/notifier.dart';
import 'tunnel_edit.dart';

class TunnelListScreen extends StatefulWidget {
  const TunnelListScreen({super.key});

  @override
  State<TunnelListScreen> createState() => _TunnelListScreenState();
}

class _TunnelListScreenState extends State<TunnelListScreen> with SingleTickerProviderStateMixin {
  List<LocalTunnel> _tunnels = [];
  bool _loading = true;
  late AnimationController _fabController;
  late Animation<double> _expandAnimation;

  Widget _buildToggleSwitch({
    required bool value,
    required ValueChanged<bool> onChanged,
  }) {
    return Switch(
      value: value,
      onChanged: onChanged,
      activeColor: Colors.white,
      inactiveThumbColor: Colors.white,
      activeTrackColor: AppTheme.vultrBlue,
      inactiveTrackColor: const Color(0xFFE6E9EF),
      trackOutlineColor: MaterialStateProperty.all(Colors.transparent),
      materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
      thumbIcon: MaterialStateProperty.resolveWith((states) {
        if (states.contains(MaterialState.selected)) {
          return const Icon(Icons.check, size: 12, color: AppTheme.vultrBlue);
        }
        return null; // No icon for inactive state
      }),
    );
  }

  @override
  void initState() {
    super.initState();
    _fabController = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 400),
      reverseDuration: const Duration(milliseconds: 250),
    );
    _expandAnimation = CurvedAnimation(
      parent: _fabController,
      curve: Curves.easeOutBack,
      reverseCurve: Curves.easeIn, // Changed from easeInBack to easeIn for immediate start
    );
    _loadTunnels();
  }

  @override
  void dispose() {
    _fabController.dispose();
    super.dispose();
  }

  void _toggleFab() {
    if (_fabController.isDismissed) {
      _fabController.forward();
    } else {
      _fabController.reverse();
    }
  }

  Future<void> _loadTunnels() async {
    try {
      final tunnels = await SmartAgentApi.getTunnels();
      setState(() {
        _tunnels = tunnels;
        _loading = false;
      });
    } catch (e) {
      setState(() => _loading = false);
      // Handle error
    }
  }

  @override
  Widget build(BuildContext context) {
    return StreamBuilder<VpnState>(
      stream: SmartAgentApi.vpnStateStream,
      builder: (context, snapshot) {
        final vpnState = snapshot.data ?? VpnState(isRunning: false);
        return Scaffold(
          body: GestureDetector(
            behavior: HitTestBehavior.opaque,
            onTap: () {
              if (_fabController.isCompleted) {
                _fabController.reverse();
              }
            },
            child: RefreshIndicator(
              onRefresh: _loadTunnels,
              child: ListView(
                padding: const EdgeInsets.all(16),
                children: [
                  _buildStatusCard(vpnState),
                  const SizedBox(height: 24),
                  Text(
                    'AVAILABLE TUNNELS',
                    style: GoogleFonts.inter(
                      color: AppTheme.textSecondary,
                      fontSize: 12,
                      fontWeight: FontWeight.w600,
                      letterSpacing: 1.0,
                    ),
                  ),
                  const SizedBox(height: 12),
                  if (_loading)
                    const Center(child: CircularProgressIndicator())
                  else if (_tunnels.isEmpty)
                    _buildEmptyState()
                  else
                    ..._tunnels.map((t) => _buildTunnelItem(t, vpnState)),
                ],
              ),
            ),
          ),
          floatingActionButton: _buildFab(),
        );
      },
    );
  }

  Widget _buildFab() {
    return SizedBox(
      width: 260, // Widen hit area so expanded actions remain tappable
      height: 56, // Standard FAB height
      child: Stack(
        alignment: Alignment.centerRight,
        clipBehavior: Clip.none,
        children: [
          // Import Tunnel Button
          _buildFabOption(
            index: 2,
            icon: Icons.cloud_upload_outlined,
            label: '导入隧道',
            onPressed: () async {
              _toggleFab();
              final success = await SmartAgentApi.importConfig();
              if (success) {
                _loadTunnels();
              }
            },
          ),
          // New Tunnel Button
          _buildFabOption(
            index: 1,
            icon: Icons.edit_note,
            label: '新增隧道',
            onPressed: () async {
              _toggleFab();
              final fileName = 'manual_${DateTime.now().millisecondsSinceEpoch}.conf';
              final template = '''
[Interface]
PrivateKey =
Address = 10.0.0.2/32
DNS = 1.1.1.1

[Peer]
PublicKey =
PresharedKey =
AllowedIPs = 0.0.0.0/0, ::/0
Endpoint = example.com:51820
PersistentKeepalive = 25
''';
              final saved = await Navigator.of(context).push<bool>(
                MaterialPageRoute(
                  builder: (_) => TunnelEditPage(
                    tunnel: LocalTunnel(name: 'NewTunnel', file: fileName),
                    initialContent: template,
                  ),
                ),
              );
              if (saved == true) {
                _loadTunnels();
              }
            },
          ),
          // Main Toggle Button
          FloatingActionButton(
            heroTag: 'fab_toggle',
            onPressed: _toggleFab,
            backgroundColor: Colors.white,
            elevation: 4,
            shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(999)),
            child: AnimatedBuilder(
              animation: _fabController,
              builder: (context, child) {
                return Transform.rotate(
                  angle: _fabController.value * 0.75 * 3.14159, // Rotate 135 degrees
                  child: Icon(
                    Icons.add,
                    color: AppTheme.vultrBlue,
                    size: 28,
                  ),
                );
              },
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildFabOption({
    required int index,
    required IconData icon,
    required String label,
    required VoidCallback onPressed,
  }) {
    return AnimatedBuilder(
      animation: _expandAnimation,
      builder: (context, child) {
        final double value = _expandAnimation.value;
        // Reduced offsets to minimize whitespace on the right
        // Index 1 (New): 70.0 from right
        // Index 2 (Import): 200.0 from right (130.0 gap maintained)
        final double endOffset = index == 1 ? 70.0 : 200.0; 
        final double xTranslation = -endOffset * value;
        // Scale width only (scaleX), keep height (scaleY) at 1.0
        final double scaleX = 0.4 + (0.6 * value);
        final double opacity = (value > 0.2 ? 1.0 : value * 5).clamp(0.0, 1.0);
        final double elevation = (6 * value).clamp(0.0, 6.0);

        return Positioned(
          right: 0,
          child: Transform.translate(
            offset: Offset(xTranslation, 0),
            child: Transform(
              transform: Matrix4.diagonal3Values(scaleX, 1.0, 1.0),
              alignment: Alignment.centerRight,
              child: Opacity(
                opacity: opacity,
                child: FloatingActionButton.extended(
                  heroTag: 'fab_option_$index',
                  onPressed: onPressed,
                  backgroundColor: Colors.white,
                  elevation: elevation,
                  shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
                  extendedPadding: const EdgeInsets.symmetric(horizontal: 16),
                  icon: Icon(icon, color: AppTheme.vultrBlue, size: 20),
                  label: Text(
                    label,
                    style: GoogleFonts.inter(
                      color: AppTheme.vultrBlue,
                      fontWeight: FontWeight.w700,
                      fontSize: 13,
                    ),
                  ),
                ),
              ),
            ),
          ),
        );
      },
    );
  }

  Widget _buildStatusCard(VpnState state) {
    final isRunning = state.isRunning;
    final color = isRunning ? const Color(0xFF10B981) : AppTheme.textSecondary;
    
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 22),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(8),
        boxShadow: AppTheme.cardShadow,
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Container(
                width: 10,
                height: 10,
                decoration: BoxDecoration(
                  color: color,
                  shape: BoxShape.circle,
                ),
              ),
              const SizedBox(width: 10),
              Text(
                isRunning ? 'CONNECTED' : 'DISCONNECTED',
                style: GoogleFonts.inter(
                  color: color,
                  fontWeight: FontWeight.w700,
                  fontSize: 14,
                ),
              ),
              const Spacer(),
              Text(
                state.lastHandshake?.isNotEmpty == true ? state.lastHandshake! : '--',
                style: GoogleFonts.inter(
                  color: AppTheme.textSecondary,
                  fontSize: 12,
                ),
              ),
            ],
          ),
          const SizedBox(height: 20),
          const Divider(height: 1, color: AppTheme.divider),
          const SizedBox(height: 20),
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              _buildStatItem('TUNNEL', isRunning ? (state.tunnelName ?? 'Unknown') : '-'),
              _buildStatItem('UPLOAD', isRunning ? _formatBytes(state.txBytes) : '-'),
              _buildStatItem('DOWNLOAD', isRunning ? _formatBytes(state.rxBytes) : '-'),
            ],
          ),
        ],
      ),
    );
  }

  Widget _buildStatItem(String label, String value) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          label,
          style: GoogleFonts.inter(
            color: AppTheme.textSecondary,
            fontSize: 11,
            fontWeight: FontWeight.w600,
          ),
        ),
        const SizedBox(height: 4),
        Text(
          value,
          style: GoogleFonts.inter(
            color: AppTheme.textPrimary,
            fontSize: 15,
            fontWeight: FontWeight.w600,
          ),
        ),
      ],
    );
  }

  Widget _buildTunnelItem(LocalTunnel tunnel, VpnState vpnState) {
    final isActive = vpnState.isRunning && vpnState.tunnelFile == tunnel.file;
    
    return Container(
        margin: const EdgeInsets.only(bottom: 12),
        decoration: BoxDecoration(
          color: Colors.white,
          borderRadius: BorderRadius.circular(8),
          boxShadow: AppTheme.cardShadow,
        ),
        child: Material(
          color: Colors.transparent,
          child: InkWell(
            onTap: () async {
              if (isActive) {
                Notifier.show(context, '请先关闭隧道后再编辑配置');
                return;
              }
              final saved = await Navigator.of(context).push<bool>(
                MaterialPageRoute(
                  builder: (_) => TunnelEditPage(tunnel: tunnel),
                ),
              );
              if (saved == true) {
                _loadTunnels();
              }
            },
            onLongPress: () async {
              if (isActive) {
                Notifier.show(context, '正在使用的隧道无法删除');
                return;
              }
              final confirm = await showDialog<bool>(
                context: context,
                barrierColor: Colors.black54,
                builder: (ctx) => AlertDialog(
                  backgroundColor: Colors.white,
                  title: const Text('删除隧道'),
                  content: Text('确定删除 ${tunnel.name} ？'),
                  actions: [
                    TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('取消')),
                    TextButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('删除')),
                  ],
                ),
              );
              if (confirm == true) {
                final ok = await SmartAgentApi.deleteTunnel(tunnel.file);
                if (ok) {
                  setState(() {
                    _tunnels.removeWhere((t) => t.file == tunnel.file);
                  });
                } else {
                  Notifier.show(context, '删除失败');
                }
              }
            },
            borderRadius: BorderRadius.circular(8),
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 22),
              child: Row(
                children: [
                  Container(
                    padding: const EdgeInsets.all(10),
                    decoration: BoxDecoration(
                      color: AppTheme.background,
                      borderRadius: BorderRadius.circular(6),
                    ),
                    child: Icon(Icons.vpn_lock, color: AppTheme.vultrBlue, size: 28),
                  ),
                  const SizedBox(width: 16),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          tunnel.name,
                          style: GoogleFonts.inter(
                            fontWeight: FontWeight.w600,
                            fontSize: 15,
                            color: AppTheme.textPrimary,
                          ),
                        ),
                        const SizedBox(height: 2),
                        Text(
                          tunnel.file,
                          style: GoogleFonts.inter(
                            fontSize: 12,
                            color: AppTheme.textSecondary,
                          ),
                        ),
                      ],
                    ),
                  ),
                  _buildToggleSwitch(
                    value: isActive,
                    onChanged: (val) {
                      SmartAgentApi.toggleTunnel(tunnel.file, val);
                    },
                  ),
                ],
              ),
            ),
          ),
        ),
      );
  }

  Widget _buildEmptyState() {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(40.0),
        child: Column(
          children: [
            Icon(Icons.layers_clear_outlined, size: 48, color: AppTheme.textSecondary),
            const SizedBox(height: 16),
            Text(
              'No Tunnels Found',
              style: GoogleFonts.inter(
                color: AppTheme.textSecondary,
                fontSize: 16,
              ),
            ),
          ],
        ),
      ),
    );
  }

  String _formatBytes(int bytes) {
    const suffixes = ["B", "KB", "MB", "GB", "TB"];
    var i = 0;
    double d = bytes.toDouble();
    if (bytes <= 0) return "0 B";
    while ((d >= 1024) && (i < suffixes.length - 1)) {
      d /= 1024;
      i++;
    }
    return '${d.toStringAsFixed(2)} ${suffixes[i]}';
  }
}
