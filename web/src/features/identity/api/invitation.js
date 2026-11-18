import { get, post, put } from '@/shared/api/http'

// 邀请能力来自 btry-identity 内核（UserInvitation 已通用化 roleCode）；
// 这里固定受邀角色为 TEACHER，前端路径沿用旧运营台，接口指向内核通用邀请 API。
export function createTeacherInvitation(body) {
  return post('/admin/invitations', { ...body, roleCode: 'TEACHER' })
}

export function listTeacherInvitations(params) {
  return get('/admin/invitations', params)
}

export function revokeTeacherInvitation(id) {
  return put(`/admin/invitations/${id}/revoke`)
}

export function previewTeacherInvitation(token) {
  return post('/auth/invitations/preview', { token })
}

export function acceptTeacherInvitation(body) {
  return post('/auth/invitations/accept', body)
}
