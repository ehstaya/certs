package com.sfquiz.entity;

/** Application roles.
 *  USER     — practices tests + can contribute questions.
 *  VERIFIER — same as USER plus is required to give a reason on every
 *             thumbs-up / thumbs-down. Verifier feedback aggregates into
 *             an admin report for quality control.
 *  ADMIN    — full management access (users, questions, reports). */
public enum UserRole {
    USER,
    VERIFIER,
    ADMIN
}
