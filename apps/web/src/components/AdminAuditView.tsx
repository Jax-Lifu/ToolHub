import { FormEvent, useCallback, useEffect, useState } from 'react';
import { RefreshCw, Shield, Trash2, UserPlus } from 'lucide-react';
import { AuditEntry, Role, UserView } from '../types';
import { Button } from './ui/button';
import { Input } from './ui/input';

interface AdminAuditViewProps {
  token: string;
  currentUsername: string;
  currentRole: Role;
  onToast: (msg: string) => void;
}

export function AdminAuditView({
  token,
  currentUsername,
  currentRole,
  onToast,
}: AdminAuditViewProps) {
  const [users, setUsers] = useState<UserView[]>([]);
  const [audit, setAudit] = useState<AuditEntry[]>([]);
  const [newUsername, setNewUsername] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [newRole, setNewRole] = useState<Role>('operator');
  const [busy, setBusy] = useState(false);

  const isAdmin = currentRole === 'admin';

  const loadAll = useCallback(async () => {
    try {
      const auditRes = await fetch('/api/audit?limit=150', {
        headers: { Authorization: `Bearer ${token}` },
        cache: 'no-store',
      });
      if (auditRes.ok) {
        setAudit(await auditRes.json());
      }
      if (isAdmin) {
        const usersRes = await fetch('/api/users', {
          headers: { Authorization: `Bearer ${token}` },
          cache: 'no-store',
        });
        if (usersRes.ok) {
          setUsers(await usersRes.json());
        }
      }
    } catch {
      // ignore transient error
    }
  }, [token, isAdmin]);

  useEffect(() => {
    loadAll();
  }, [loadAll]);

  const handleCreateUser = async (e: FormEvent) => {
    e.preventDefault();
    if (!newUsername.trim() || newPassword.length < 4) {
      onToast('用户名至少 2 位，密码至少 4 位');
      return;
    }
    setBusy(true);
    try {
      const res = await fetch('/api/users', {
        method: 'POST',
        headers: {
          Authorization: `Bearer ${token}`,
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          username: newUsername.trim(),
          password: newPassword,
          role: newRole,
        }),
      });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) {
        onToast(data.error || `创建失败 (HTTP ${res.status})`);
        return;
      }
      setNewUsername('');
      setNewPassword('');
      onToast(`已创建账号 ${newUsername.trim()} (${newRole})`);
      await loadAll();
    } finally {
      setBusy(false);
    }
  };

  const handleRoleChange = async (username: string, role: Role) => {
    const res = await fetch(`/api/users/${encodeURIComponent(username)}`, {
      method: 'PATCH',
      headers: {
        Authorization: `Bearer ${token}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ role }),
    });
    const data = await res.json().catch(() => ({}));
    if (!res.ok) {
      onToast(data.error || '修改权限失败');
      return;
    }
    onToast(`已更新 ${username} 权限为 ${role}`);
    loadAll();
  };

  const handleDeleteUser = async (username: string) => {
    const res = await fetch(`/api/users/${encodeURIComponent(username)}`, {
      method: 'DELETE',
      headers: { Authorization: `Bearer ${token}` },
    });
    const data = await res.json().catch(() => ({}));
    if (!res.ok) {
      onToast(data.error || '删除失败');
      return;
    }
    onToast(`已删除账号 ${username}`);
    loadAll();
  };

  return (
    <div className="space-y-4">
      {isAdmin ? (
        <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
          <section className="rounded-xl border border-border bg-card p-5 space-y-4">
            <div className="flex items-center gap-2">
              <UserPlus className="size-4 text-primary" />
              <div>
                <h2 className="text-base font-semibold">添加团队账号与角色权限</h2>
                <p className="text-xs text-muted-foreground">
                  admin（全部权限）· operator（运维控制）· viewer（只读查看）
                </p>
              </div>
            </div>
            <form onSubmit={handleCreateUser} className="grid grid-cols-1 gap-3 sm:grid-cols-3">
              <Input
                value={newUsername}
                onChange={(e) => setNewUsername(e.target.value)}
                placeholder="用户名 (如 qa1)"
              />
              <Input
                type="password"
                value={newPassword}
                onChange={(e) => setNewPassword(e.target.value)}
                placeholder="初始密码 (≥4位)"
              />
              <select
                value={newRole}
                onChange={(e) => setNewRole(e.target.value as Role)}
              >
                <option value="operator">operator（运维）</option>
                <option value="viewer">viewer（只读）</option>
                <option value="admin">admin（管理员）</option>
              </select>
              <div className="sm:col-span-3">
                <Button type="submit" size="sm" disabled={busy}>
                  <UserPlus className="size-3.5" />
                  <span>创建账号</span>
                </Button>
              </div>
            </form>
          </section>

          <section className="rounded-xl border border-border bg-card p-5 space-y-3">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2">
                <Shield className="size-4 text-primary" />
                <div>
                  <h2 className="text-base font-semibold">现有账号 ({users.length})</h2>
                  <p className="text-xs text-muted-foreground">当前登录：{currentUsername}</p>
                </div>
              </div>
              <Button variant="outline" size="icon-sm" onClick={loadAll}>
                <RefreshCw className="size-3.5" />
              </Button>
            </div>
            <div className="space-y-2 max-h-60 overflow-y-auto">
              {users.map((u) => (
                <div
                  key={u.username}
                  className="flex flex-wrap items-center justify-between gap-2 rounded-lg border border-border bg-muted/30 px-3 py-2 text-xs"
                >
                  <div>
                    <div className="font-semibold mono">{u.username}</div>
                    <div className="text-[11px] text-muted-foreground">
                      {new Date(u.createdAt).toLocaleString()}
                    </div>
                  </div>
                  <div className="flex items-center gap-2">
                    <select
                      value={u.role}
                      onChange={(e) => handleRoleChange(u.username, e.target.value as Role)}
                    >
                      <option value="admin">admin</option>
                      <option value="operator">operator</option>
                      <option value="viewer">viewer</option>
                    </select>
                    {u.username !== currentUsername && (
                      <Button
                        variant="destructive"
                        size="icon-sm"
                        onClick={() => handleDeleteUser(u.username)}
                        aria-label={`删除 ${u.username}`}
                      >
                        <Trash2 className="size-3.5" />
                      </Button>
                    )}
                  </div>
                </div>
              ))}
            </div>
          </section>
        </div>
      ) : (
        <div className="rounded-xl border border-border bg-card p-4 text-xs text-muted-foreground">
          当前账号 <strong className="text-foreground">{currentUsername}</strong> 权限为{' '}
          <strong className="text-foreground">{currentRole}</strong>，仅 <code>admin</code> 可管理团队账号。
        </div>
      )}

      <section className="rounded-xl border border-border bg-card p-5 space-y-3">
        <div className="flex items-center justify-between">
          <div>
            <h2 className="text-base font-semibold">操作审计日志 ({audit.length})</h2>
            <p className="text-xs text-muted-foreground">
              自动记录登录、设备分组修改、压测/重启/setprop 及账号权限变更
            </p>
          </div>
          <Button variant="outline" size="sm" onClick={loadAll}>
            <RefreshCw className="size-3.5" />
            <span>刷新</span>
          </Button>
        </div>
        <div className="table-box" style={{ maxBlockSize: '50vh' }}>
          <table className="fleet-table">
            <thead>
              <tr>
                <th>时间</th>
                <th>操作人 / 角色</th>
                <th>动作</th>
                <th>目标</th>
                <th>详情</th>
              </tr>
            </thead>
            <tbody>
              {audit.map((a, idx) => (
                <tr key={`${a.ts}-${idx}`}>
                  <td className="mono text-xs text-muted-foreground">
                    {new Date(a.ts).toLocaleString()}
                  </td>
                  <td className="text-xs">
                    <span className="font-semibold">{a.actor}</span>{' '}
                    <span className="rounded bg-muted px-1.5 py-0.5 text-[11px]">{a.role}</span>
                  </td>
                  <td className="mono text-xs font-medium text-primary">{a.action}</td>
                  <td className="mono text-xs">{a.target}</td>
                  <td className="mono text-xs text-muted-foreground break-all">{a.detail}</td>
                </tr>
              ))}
              {audit.length === 0 && (
                <tr>
                  <td colSpan={5} className="py-8 text-center text-xs text-muted-foreground">
                    暂无审计日志
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </section>
    </div>
  );
}
