import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import { AuthProvider } from './auth/AuthContext';
import Layout from './components/Layout';
import Protected from './components/Protected';
import AiAnalyticsPage from './pages/AiAnalyticsPage';
import AiChatPage from './pages/AiChatPage';
import AiPrioritizePage from './pages/AiPrioritizePage';
import ClientCardPage from './pages/ClientCardPage';
import ClientFormPage from './pages/ClientFormPage';
import ClientsPage from './pages/ClientsPage';
import ImportPage from './pages/ImportPage';
import LoginPage from './pages/LoginPage';
import NotFoundPage from './pages/NotFoundPage';
import TodayPage from './pages/TodayPage';

export default function App() {
  return (
    <AuthProvider>
      <BrowserRouter>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          {/* Всё, кроме /login, — за аутентификацией; при истечении сессии
              Protected реактивно вернёт сюда. */}
          <Route
            path="/"
            element={
              <Protected>
                <Layout />
              </Protected>
            }
          >
            <Route index element={<Navigate to="/clients" replace />} />
            <Route path="clients" element={<ClientsPage />} />
            <Route path="today" element={<TodayPage />} />
            <Route path="ai" element={<AiChatPage />} />
            <Route
              path="ai/prioritize"
              element={
                <Protected adminOnly>
                  <AiPrioritizePage />
                </Protected>
              }
            />
            <Route path="ai/analytics" element={<AiAnalyticsPage />} />
            <Route
              path="clients/new"
              element={
                <Protected adminOnly>
                  <ClientFormPage />
                </Protected>
              }
            />
            <Route path="clients/:id" element={<ClientCardPage />} />
            <Route
              path="clients/:id/edit"
              element={
                <Protected adminOnly>
                  <ClientFormPage />
                </Protected>
              }
            />
            <Route
              path="import"
              element={
                <Protected adminOnly>
                  <ImportPage />
                </Protected>
              }
            />
            <Route path="*" element={<NotFoundPage />} />
          </Route>
        </Routes>
      </BrowserRouter>
    </AuthProvider>
  );
}
