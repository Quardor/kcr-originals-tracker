import { Navigate, useLocation } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';

/** Пускает только вошедших; adminOnly — только администраторов. */
export default function Protected({ adminOnly = false, children }) {
  const { user } = useAuth();
  const location = useLocation();

  if (!user) {
    return <Navigate to="/login" replace state={{ from: location }} />;
  }
  if (adminOnly && user.role !== 'ADMIN') {
    return <Navigate to="/clients" replace />;
  }
  return children;
}
