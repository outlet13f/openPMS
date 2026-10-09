// RFC 9457 problem details, as returned by the API (architecture.md §4.1).

export interface FieldError {
  field: string;
  message: string;
}

export class ApiError extends Error {
  readonly status: number;
  readonly title: string;
  readonly fieldErrors: FieldError[];
  /** For 409 conflicts the API sends the current version of the resource. */
  readonly current?: unknown;

  constructor(status: number, title: string, detail?: string, opts?: { fieldErrors?: FieldError[]; current?: unknown }) {
    super(detail ?? title);
    this.name = "ApiError";
    this.status = status;
    this.title = title;
    this.fieldErrors = opts?.fieldErrors ?? [];
    this.current = opts?.current;
  }

  get isConflict() {
    return this.status === 409;
  }
  get isValidation() {
    return this.status === 422;
  }
  get isNotFound() {
    return this.status === 404;
  }
  get isUnauthenticated() {
    return this.status === 401;
  }
}

export function isApiError(e: unknown): e is ApiError {
  return e instanceof ApiError;
}
