import { useEffect, useMemo, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { createClient, fetchClient, updateClient } from '../api/clients';
import { errorMessage } from '../api/errors';
import { ErrorBox, Spinner } from '../components/ui';

const CLIENT_TYPES = [
  { value: '', label: '— не указан —' },
  { value: 'VIP', label: 'VIP' },
  { value: 'TECH_PARTNER', label: 'Технологический партнёр' },
  { value: 'WORKS_VIA_MP', label: 'Работает через маркетплейс' },
  { value: 'NEWCOMER', label: 'Новичок' },
];

const KCR_CODE_PATTERN = /^[A-Z]{3}\d{5}$/;
const INN_PATTERN = /^(\d{10}|\d{12})$/;
const KPP_PATTERN = /^\d{9}$/;
const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

// Булевы поля клиента nullable: '' → нет данных (null), иначе true/false.
const boolFromForm = (value) => (value === '' ? null : value === 'true');

function Field({ label, required = false, hint, children }) {
  return (
    <label className="field">
      <span>
        {label}
        {required && <span className="required"> *</span>}
        {hint && <span className="field-hint"> — {hint}</span>}
      </span>
      {children}
    </label>
  );
}

export default function ClientFormPage() {
  const { id } = useParams();
  const isEdit = Boolean(id);
  const navigate = useNavigate();

  const [form, setForm] = useState({
    kcrCode: '',
    inn: '',
    kpp: '',
    organizationName: '',
    internalId: '',
    clientType: '',
    kcrActive: '',
    kcrBlocked: '',
    tariff: '',
    ekbTravelTime: '',
    manager: '',
    flags: '',
    contactPerson: '',
    contactPhone: '',
    contactEmail: '',
  });
  const [original, setOriginal] = useState(null);
  const [loading, setLoading] = useState(isEdit);
  const [error, setError] = useState(null);
  const [fieldErrors, setFieldErrors] = useState({});
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (!isEdit) return undefined;
    let cancelled = false;
    fetchClient(id)
      .then((client) => {
        if (cancelled) return;
        const value = {
          kcrCode: client.kcrCode ?? '',
          inn: client.inn ?? '',
          kpp: client.kpp ?? '',
          organizationName: client.organizationName ?? '',
          internalId: client.internalId ?? '',
          clientType: client.clientType ?? '',
          kcrActive: client.kcrActive === null || client.kcrActive === undefined ? '' : String(client.kcrActive),
          kcrBlocked: client.kcrBlocked === null || client.kcrBlocked === undefined ? '' : String(client.kcrBlocked),
          tariff: client.tariff ?? '',
          ekbTravelTime: client.ekbTravelTime ?? '',
          manager: client.manager ?? '',
          flags: (client.flags ?? []).join(', '),
          contactPerson: client.contactPerson ?? '',
          contactPhone: client.contactPhone ?? '',
          contactEmail: client.contactEmail ?? '',
        };
        setForm(value);
        // kcrCode неизменяем (нет в DTO PATCH) — в diff не участвует.
        setOriginal({ ...value, kcrCode: undefined });
      })
      .catch((err) => {
        if (!cancelled) setError(errorMessage(err));
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [id, isEdit]);

  const set = (name) => (event) => {
    const { value } = event.target;
    setForm((prev) => ({ ...prev, [name]: value }));
  };

  const parsedFlags = useMemo(
    () =>
      form.flags
        .split(',')
        .map((f) => f.trim())
        .filter(Boolean),
    [form.flags]
  );

  const validate = () => {
    const errors = {};
    if (!isEdit) {
      if (!KCR_CODE_PATTERN.test(form.kcrCode.trim().toUpperCase())) {
        errors.kcrCode = 'Формат: 3 латинские буквы и 5 цифр (например ABC12345)';
      }
      if (!INN_PATTERN.test(form.inn.trim())) {
        errors.inn = 'ИНН — 10 или 12 цифр';
      }
      if (!form.organizationName.trim()) {
        errors.organizationName = 'Обязательное поле';
      }
    } else {
      if (form.inn.trim() && !INN_PATTERN.test(form.inn.trim())) errors.inn = 'ИНН — 10 или 12 цифр';
      if (form.organizationName.trim() === '' && form.organizationName !== original?.organizationName) {
        // пустое название не отправляется вовсе — «не менять»; отдельно не валидируем
      }
    }
    if (form.kpp.trim() && !KPP_PATTERN.test(form.kpp.trim())) {
      errors.kpp = 'КПП — 9 цифр';
    }
    if (form.contactEmail.trim() && !EMAIL_PATTERN.test(form.contactEmail.trim())) {
      errors.contactEmail = 'Некорректный email';
    }
    setFieldErrors(errors);
    return Object.keys(errors).length === 0;
  };

  const buildBody = () => {
    const body = {};

    if (!isEdit) {
      body.kcrCode = form.kcrCode.trim().toUpperCase();
      body.inn = form.inn.trim();
      body.organizationName = form.organizationName.trim();
      body.kcrActive = boolFromForm(form.kcrActive);
      body.kcrBlocked = boolFromForm(form.kcrBlocked);
    } else {
      // PATCH: null = «не менять»; пустое поле в форме тоже означает «не менять»,
      // очистить значение через PATCH нельзя (контракт бэкенда).
      const trimmed = (name) => form[name].trim();
      if (trimmed('inn') && trimmed('inn') !== original.inn) body.inn = trimmed('inn');
      if (trimmed('organizationName') && trimmed('organizationName') !== original.organizationName) {
        body.organizationName = trimmed('organizationName');
      }
      if (form.kcrActive !== original.kcrActive) body.kcrActive = boolFromForm(form.kcrActive);
      if (form.kcrBlocked !== original.kcrBlocked) body.kcrBlocked = boolFromForm(form.kcrBlocked);
    }

    const optional = (name, transform = (v) => v) => {
      const value = transform(form[name].trim());
      if (value && (!original || value !== transform(original[name].trim()))) {
        body[name] = value;
      }
    };

    optional('kpp');
    optional('internalId');
    optional('clientType');
    optional('tariff');
    optional('ekbTravelTime');
    optional('manager');
    optional('contactPerson');
    optional('contactPhone');
    optional('contactEmail');

    const flagsJoined = parsedFlags.join(', ');
    if (parsedFlags.length > 0 && (!original || flagsJoined !== original.flags)) {
      body.flags = parsedFlags;
    }

    return body;
  };

  const submit = async (event) => {
    event.preventDefault();
    setError(null);
    if (isEdit && !original) return; // карточка не загрузилась — править нечего
    if (!validate()) return;

    setBusy(true);
    try {
      const saved = isEdit
        ? await updateClient(id, buildBody())
        : await createClient(buildBody());
      navigate(`/clients/${saved.id}`);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  };

  if (loading) return <Spinner />;

  const fieldError = (name) =>
    fieldErrors[name] ? <span className="field-error">{fieldErrors[name]}</span> : null;

  return (
    <>
      <div className="page-head">
        <div>
          <Link to={isEdit ? `/clients/${id}` : '/clients'} className="back-link">
            ← {isEdit ? 'К карточке клиента' : 'К списку клиентов'}
          </Link>
          <h1>{isEdit ? 'Изменение клиента' : 'Новый клиент'}</h1>
        </div>
      </div>

      <ErrorBox message={error} />

      <form className="card form-card" onSubmit={submit} noValidate>
        <div className="form-grid">
          {!isEdit && (
            <Field label="Код KCR" required hint="3 латинские буквы + 5 цифр">
              <input value={form.kcrCode} onChange={set('kcrCode')} placeholder="ABC12345" />
              {fieldError('kcrCode')}
            </Field>
          )}
          <Field label="ИНН" required={!isEdit} hint="10 или 12 цифр">
            <input value={form.inn} onChange={set('inn')} inputMode="numeric" />
            {fieldError('inn')}
          </Field>
          <Field label="КПП" hint="9 цифр">
            <input value={form.kpp} onChange={set('kpp')} inputMode="numeric" />
            {fieldError('kpp')}
          </Field>
          <Field label="Название организации" required={!isEdit}>
            <input value={form.organizationName} onChange={set('organizationName')} />
            {fieldError('organizationName')}
          </Field>
          <Field label="Внутренний ID / лицевой счёт">
            <input value={form.internalId} onChange={set('internalId')} />
          </Field>
          <Field label="Тип клиента">
            <select value={form.clientType} onChange={set('clientType')}>
              {CLIENT_TYPES.map((t) => (
                <option key={t.value} value={t.value}>{t.label}</option>
              ))}
            </select>
          </Field>
          <Field label="KCR действует">
            <select value={form.kcrActive} onChange={set('kcrActive')}>
              <option value="">Нет данных</option>
              <option value="true">Да</option>
              <option value="false">Нет</option>
            </select>
          </Field>
          <Field label="KCR заблокирован">
            <select value={form.kcrBlocked} onChange={set('kcrBlocked')}>
              <option value="">Нет данных</option>
              <option value="true">Да</option>
              <option value="false">Нет</option>
            </select>
          </Field>
          <Field label="Тариф">
            <input value={form.tariff} onChange={set('tariff')} />
          </Field>
          <Field label="Время от Екатеринбурга">
            <input value={form.ekbTravelTime} onChange={set('ekbTravelTime')} />
          </Field>
          <Field label="Менеджер">
            <input value={form.manager} onChange={set('manager')} />
          </Field>
          <Field label="Специальные признаки" hint="через запятую (напр. VIP, интегратор)">
            <input value={form.flags} onChange={set('flags')} />
          </Field>
          <Field label="Контактное лицо">
            <input value={form.contactPerson} onChange={set('contactPerson')} />
          </Field>
          <Field label="Контактный телефон">
            <input value={form.contactPhone} onChange={set('contactPhone')} />
          </Field>
          <Field label="Email">
            <input type="email" value={form.contactEmail} onChange={set('contactEmail')} />
            {fieldError('contactEmail')}
          </Field>
        </div>

        {isEdit && (
          <p className="hint">
            Заполняются только поля, которые нужно изменить; пустое поле — «не менять».
            Код KCR не изменяется.
          </p>
        )}

        <div className="form-actions">
          <button type="submit" className="btn btn-primary" disabled={busy || (isEdit && !original)}>
            {busy ? 'Сохранение…' : 'Сохранить'}
          </button>
          <Link to={isEdit ? `/clients/${id}` : '/clients'} className="btn btn-ghost">
            Отмена
          </Link>
        </div>
      </form>
    </>
  );
}
