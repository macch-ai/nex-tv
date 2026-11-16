import { useEffect, useState } from 'react';
import { KeyRound } from 'lucide-react';
import { getTechPinStatus, setTechPin, type TechPinStatus } from './api';
import { useStepUp } from './stepUp';
import { hashTechPin, techPinProblem } from './techPin';
import { useToast } from './toast';
import { Badge, Button, Card, ErrorText, Field, inputClass } from './ui';

/**
 * PIN del acceso técnico de los TV (Ajustes → Acceso técnico). companyId null = el global de NEX, que vale para equipos
 * sin vincular y empresas sin PIN propio. El PIN se convierte en hash en el navegador; el servidor nunca lo ve.
 */
export function TechPinCard({ companyId, className = '' }: { companyId: string | null; className?: string }) {
  const stepUp = useStepUp();
  const toast = useToast();
  const [status, setStatus] = useState<TechPinStatus | null>(null);
  const [pin, setPin] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [tick, setTick] = useState(0);

  useEffect(() => {
    let alive = true;
    getTechPinStatus(companyId).then((s) => alive && setStatus(s)).catch(() => alive && setStatus(null));
    return () => { alive = false; };
  }, [companyId, tick]);

  const own = companyId ? status?.company === true : status?.global === true;
  const save = async (clear: boolean) => {
    setError(null);
    if (!clear) {
      const problem = techPinProblem(pin);
      if (problem) return setError(problem);
    }
    setBusy(true);
    try {
      const hash = clear ? null : await hashTechPin(pin);
      await stepUp.guard(() => setTechPin(companyId, hash));
      setPin('');
      setTick((t) => t + 1);
      toast.show(clear ? 'PIN técnico borrado' : 'PIN técnico guardado. Los TV lo reciben en un minuto.');
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const description = companyId
    ? 'Lo piden los TV en Ajustes → Acceso técnico. Pasáselo solo a tus instaladores. Si no definís uno, vale el de NEX.'
    : 'Vale en los TV sin vincular y en las empresas que no definieron el suyo.';

  return (
    <Card
      title="PIN técnico"
      description={description}
      className={className}
      action={status && <Badge tone={own ? 'success' : 'neutral'}>{own ? 'Definido' : companyId ? 'Usa el de NEX' : 'Sin definir'}</Badge>}
    >
      <div className="flex flex-col gap-4">
        <Field label={own ? 'Nuevo PIN' : 'PIN'} hint="4 números. NEX no lo guarda: si se olvida, se define uno nuevo.">
          <input
            className={`${inputClass} font-mono tracking-[0.4em]`}
            inputMode="numeric"
            autoComplete="off"
            maxLength={4}
            value={pin}
            onChange={(e) => setPin(e.target.value.replace(/\D/g, '').slice(0, 4))}
            placeholder="••••"
          />
        </Field>
        <ErrorText error={error} />
        <div className="flex gap-2">
          <Button icon={KeyRound} disabled={busy || pin.length !== 4} loading={busy} onClick={() => void save(false)} className="flex-1">Guardar PIN</Button>
          {own && (
            <Button variant="ghost" disabled={busy} onClick={() => void save(true)} className="text-danger hover:bg-danger-soft hover:text-danger">
              {companyId ? 'Usar el de NEX' : 'Borrar'}
            </Button>
          )}
        </div>
      </div>
    </Card>
  );
}
