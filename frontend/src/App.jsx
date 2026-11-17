import { AuthProvider } from './context/AuthContext';
import { ClusterProvider } from './context/ClusterContext';
import AppRoutes from './routes/AppRoutes';

export default function App() {
  return (
    <AuthProvider>
      <ClusterProvider>
        <AppRoutes />
      </ClusterProvider>
    </AuthProvider>
  );
}
