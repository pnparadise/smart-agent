import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:flutter/services.dart';
import '../api.dart';
import '../models.dart';
import '../theme.dart';
import '../widgets/notifier.dart';

class TunnelEditPage extends StatefulWidget {
  final LocalTunnel tunnel;
  final String? initialContent;
  const TunnelEditPage({super.key, required this.tunnel, this.initialContent});

  @override
  State<TunnelEditPage> createState() => _TunnelEditPageState();
}

class _TunnelEditPageState extends State<TunnelEditPage> {
  final _controller = TextEditingController();
  final _nameController = TextEditingController();
  bool _loading = true;
  bool _saving = false;
  int _charCount = 0;
  bool _softWrap = false;
  late bool _isNew;
  late String _targetFile;

  @override
  void initState() {
    super.initState();
    _isNew = widget.initialContent != null;
    _nameController.text = widget.tunnel.name;
    _targetFile = widget.tunnel.file;
    if (_isNew) {
      _nameController.addListener(_syncFileName);
      _syncFileName();
    }
    _load();
  }

  Future<void> _load() async {
    if (widget.initialContent != null) {
      final content = widget.initialContent!;
      setState(() {
        _controller.text = content;
        _charCount = content.length;
        _loading = false;
      });
      return;
    }
    final content = await SmartAgentApi.getTunnelConfig(widget.tunnel.file) ?? '';
    setState(() {
      _controller.text = content;
      _charCount = content.length;
      _loading = false;
    });
  }

  String _sanitizeName(String name) {
    final trimmed = name.trim();
    if (trimmed.isEmpty) return '';
    final buffer = StringBuffer();
    for (final char in trimmed.characters) {
      final code = char.codeUnitAt(0);
      final isAllowed = (code >= 48 && code <= 57) || // 0-9
          (code >= 65 && code <= 90) || // A-Z
          (code >= 97 && code <= 122) || // a-z
          char == '_' ||
          char == '-';
      if (isAllowed) buffer.write(char);
    }
    final filtered = buffer.toString();
    if (filtered.isEmpty) return '';
    final compact = filtered.replaceAll(RegExp(r'_+'), '_').replaceAll(RegExp(r'-+'), '-');
    return compact.toLowerCase();
  }

  void _syncFileName() {
    final base = _sanitizeName(_nameController.text);
    setState(() {
      _targetFile = base.endsWith('.conf') ? base : '$base.conf';
    });
  }

  Future<void> _save() async {
    final sanitizedName = _sanitizeName(_nameController.text);
    final targetFile = _isNew ? _targetFile : widget.tunnel.file;
    var content = _controller.text;
    final displayName = _isNew
        ? (sanitizedName.isEmpty ? 'new_tunnel' : sanitizedName)
        : widget.tunnel.name;

    setState(() => _saving = true);
    final ok = await SmartAgentApi.saveTunnelConfig(targetFile, content, displayName);
    setState(() => _saving = false);
    if (!mounted) return;
    if (ok) {
      Notifier.show(context, '已保存配置');
      Navigator.of(context).pop(true);
    } else {
      Notifier.show(context, '保存失败');
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text('编辑隧道', style: GoogleFonts.inter(fontWeight: FontWeight.w600)),
        actions: [
          TextButton(
            onPressed: _saving ? null : _save,
            child: _saving
                ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
                : const Text('保存'),
          ),
        ],
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : LayoutBuilder(
              builder: (context, constraints) {
                final content = Padding(
                  padding: const EdgeInsets.all(16),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      if (_isNew) ...[
                        TextField(
                          controller: _nameController,
                          onChanged: (_) => _syncFileName(),
                          inputFormatters: [
                            FilteringTextInputFormatter.allow(RegExp(r'[A-Za-z0-9_-]')),
                          ],
                          decoration: InputDecoration(
                            labelText: '隧道名称',
                            border: OutlineInputBorder(borderRadius: BorderRadius.circular(10)),
                            contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
                          ),
                        ),
                        const SizedBox(height: 8),
                        Text('文件名：$_targetFile', style: GoogleFonts.inter(color: AppTheme.textSecondary, fontSize: 12)),
                        const SizedBox(height: 8),
                        Align(
                          alignment: Alignment.centerRight,
                          child: Text(
                            '字数：$_charCount',
                            style: GoogleFonts.inter(color: AppTheme.textSecondary, fontSize: 12),
                          ),
                        ),
                        const SizedBox(height: 12),
                      ] else ...[
                        Row(
                          children: [
                            Expanded(
                              child: Column(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  Text(widget.tunnel.name, style: GoogleFonts.inter(fontSize: 16, fontWeight: FontWeight.w700)),
                                  const SizedBox(height: 4),
                                  Text(widget.tunnel.file, style: GoogleFonts.inter(color: AppTheme.textSecondary)),
                                ],
                              ),
                            ),
                            Text(
                              '字数：$_charCount',
                              style: GoogleFonts.inter(color: AppTheme.textSecondary, fontSize: 12),
                            ),
                          ],
                        ),
                        const SizedBox(height: 12),
                      ],
                      Expanded(
                        child: Container(
                          width: double.infinity,
                          height: double.infinity,
                          color: Colors.white,
                          child: TextField(
                            controller: _controller,
                            onChanged: (val) => setState(() => _charCount = val.length),
                            keyboardType: TextInputType.multiline,
                            textAlignVertical: TextAlignVertical.top,
                            textAlign: TextAlign.start,
                            expands: true,
                            maxLines: null,
                            decoration: const InputDecoration(
                              alignLabelWithHint: true,
                              hintText: 'WireGuard 配置',
                              border: InputBorder.none,
                              enabledBorder: InputBorder.none,
                              focusedBorder: InputBorder.none,
                            ),
                            style: GoogleFonts.robotoMono(fontSize: 14),
                          ),
                        ),
                      ),
                    ],
                  ),
                );

                return ConstrainedBox(
                  constraints: BoxConstraints(minHeight: constraints.maxHeight, minWidth: constraints.maxWidth),
                  child: content,
                );
              },
            ),
    );
  }

  @override
  void dispose() {
    _nameController.dispose();
    _controller.dispose();
    super.dispose();
  }

  Widget _buildEditor(double maxWidth, double maxHeight) {
    final baseField = TextField(
      controller: _controller,
      onChanged: (val) => setState(() => _charCount = val.length),
      maxLines: null,
      minLines: 6,
      textAlignVertical: TextAlignVertical.top,
      textAlign: TextAlign.start,
      decoration: const InputDecoration(
        alignLabelWithHint: true,
        hintText: 'WireGuard 配置',
        border: InputBorder.none,
        enabledBorder: InputBorder.none,
        focusedBorder: InputBorder.none,
      ),
      style: GoogleFonts.robotoMono(fontSize: 14),
    );

    final boundedHeight = maxHeight.isFinite && maxHeight > 0 ? maxHeight : 400.0;

    if (_softWrap) {
      return SizedBox(
        width: maxWidth,
        height: boundedHeight,
        child: baseField,
      );
    }

    return SizedBox(
      width: maxWidth,
      height: boundedHeight,
      child: SingleChildScrollView(
        scrollDirection: Axis.horizontal,
        child: ConstrainedBox(
          constraints: BoxConstraints(minWidth: maxWidth),
          child: baseField,
        ),
      ),
    );
  }
}
