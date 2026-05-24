package com.sfquiz.entity;

/** Application roles.
 *  USER       — practices tests + can contribute questions.
 *  VERIFIER   — same as USER plus is required to give a reason on every
 *               thumbs-up / thumbs-down. Verifier feedback aggregates into
 *               an admin report for quality control.
 *  ADMIN      — domain admin. Manages questions for the exam(s) they're
 *               assigned to (via DomainAdminAssignment). With no exam
 *               assignments they're effectively powerless until the
 *               SUPERADMIN assigns at least one exam.
 *  SUPERADMIN — full platform access. Only role allowed to create/edit/
 *               delete users, change roles, and assign domain admins to
 *               exams. Has implicit management access to every exam. */
public enum UserRole {
    USER,
    VERIFIER,
    ADMIN,
    SUPERADMIN
}
